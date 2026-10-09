import {
  Body,
  Controller,
  Get,
  Header,
  HttpCode,
  Inject,
  Post,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import { IsInt, IsOptional, IsString, Max, MaxLength, Min, MinLength } from 'class-validator';
import type { Request, Response } from 'express';
import { Public } from '../../common/decorators/public.decorator';
import { randomToken } from '../../common/utils/crypto';
import { clientIpHash } from '../../common/utils/client-ip';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { AdminGuard } from './admin.guard';
import { renderAdminPage } from './admin.page';
import { AdminService, AdminUserRow } from './admin.service';

class AdminLoginDto {
  @IsString()
  @MinLength(1)
  @MaxLength(200)
  password!: string;
}

class ListUsersQuery {
  @IsOptional()
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsString()
  @MaxLength(60)
  q?: string;

  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(10000)
  page: number = 1;

  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  limit: number = 50;
}

@ApiExcludeController()
@Public()
@Controller('admin')
export class AdminController {
  constructor(
    private readonly admin: AdminService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  @Post('login')
  @HttpCode(200)
  async login(
    @Body() body: AdminLoginDto,
    @Req() req: Request,
  ): Promise<{ token: string; expiresAt: string }> {
    const result = await this.admin.login(
      body.password,
      clientIpHash(req, this.config.auth.ipHashSalt),
    );
    return { token: result.token, expiresAt: result.expiresAt.toISOString() };
  }

  @Get('users')
  @UseGuards(AdminGuard)
  @Header('Cache-Control', 'no-store')
  users(@Query() query: ListUsersQuery): Promise<{ total: number; items: AdminUserRow[] }> {
    return this.admin.listUsers(query);
  }
}

/** The page itself, served at /admin (outside the API prefix, see app.setup.ts). */
@ApiExcludeController()
@Public()
@Controller()
export class AdminPageController {
  constructor(private readonly admin: AdminService) {}

  @Get('admin')
  page(@Res() res: Response): void {
    this.admin.requireEnabled();
    const nonce = randomToken(16);
    res.setHeader(
      'Content-Security-Policy',
      [
        "default-src 'none'",
        `script-src 'nonce-${nonce}'`,
        `style-src 'nonce-${nonce}'`,
        "connect-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'",
      ].join('; '),
    );
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    res.type('html').send(renderAdminPage(nonce));
  }
}
