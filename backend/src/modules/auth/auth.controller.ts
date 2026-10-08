import { Body, Controller, Delete, Get, HttpCode, Inject, Param, Post, Req } from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiCreatedResponse,
  ApiNoContentResponse,
  ApiOkResponse,
  ApiOperation,
  ApiTags,
} from '@nestjs/swagger';
import type { Request } from 'express';
import { CurrentPrincipal, Principal } from '../../common/decorators/current-principal.decorator';
import { Public } from '../../common/decorators/public.decorator';
import { AppError } from '../../common/errors/app-error';
import { clientIpHash } from '../../common/utils/client-ip';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { AuthResult, AuthService, RequestContext } from './auth.service';
import {
  AuthResponseDto,
  LoginDto,
  RefreshDto,
  RegisterDto,
  SessionListDto,
  TokenPairDto,
} from './dto/auth.dto';
import { SessionService, TokenPair } from './session.service';

function toTokenPairDto(pair: TokenPair): TokenPairDto {
  return {
    accessToken: pair.accessToken,
    accessTokenExpiresAt: pair.accessTokenExpiresAt.toISOString(),
    refreshToken: pair.refreshToken,
    refreshTokenExpiresAt: pair.refreshTokenExpiresAt.toISOString(),
  };
}

function toAuthResponse(result: AuthResult): AuthResponseDto {
  return {
    ...toTokenPairDto(result),
    user: result.user,
    device: { id: result.device.id, type: result.device.type, name: result.device.name },
    session: { id: result.sessionId },
  };
}

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly sessions: SessionService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  private context(req: Request): RequestContext {
    return {
      ipHash: clientIpHash(req, this.config.auth.ipHashSalt),
      userAgent: req.headers['user-agent'],
    };
  }

  @Public()
  @Post('register')
  @ApiOperation({ summary: 'Create an account with name, mobile number and password; signs in.' })
  @ApiCreatedResponse({ type: AuthResponseDto })
  async register(@Body() body: RegisterDto, @Req() req: Request): Promise<AuthResponseDto> {
    return toAuthResponse(await this.auth.register(body, this.context(req)));
  }

  @Public()
  @Post('login')
  @HttpCode(200)
  @ApiOperation({ summary: 'Sign in with mobile number and password.' })
  @ApiOkResponse({ type: AuthResponseDto })
  async login(@Body() body: LoginDto, @Req() req: Request): Promise<AuthResponseDto> {
    return toAuthResponse(await this.auth.login(body, this.context(req)));
  }

  @Public()
  @Post('refresh')
  @HttpCode(200)
  @ApiOperation({ summary: 'Exchange a refresh token for a new token pair (single use).' })
  @ApiOkResponse({ type: TokenPairDto })
  async refresh(@Body() body: RefreshDto, @Req() req: Request): Promise<TokenPairDto> {
    return toTokenPairDto(await this.auth.refresh(body.refreshToken, this.context(req)));
  }

  @Post('logout')
  @HttpCode(204)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Sign out of this device.' })
  @ApiNoContentResponse()
  async logout(@CurrentPrincipal() principal: Principal): Promise<void> {
    await this.sessions.revoke(principal.sessionId, 'LOGOUT');
  }

  @Post('logout-all')
  @HttpCode(204)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Sign out of every device.' })
  @ApiNoContentResponse()
  async logoutAll(@CurrentPrincipal() principal: Principal): Promise<void> {
    await this.sessions.revokeAllForUser(principal.userId);
  }

  @Get('sessions')
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Devices currently signed in to this account.' })
  @ApiOkResponse({ type: SessionListDto })
  async list(@CurrentPrincipal() principal: Principal): Promise<SessionListDto> {
    const rows = await this.sessions.listForUser(principal.userId);
    return {
      items: rows.map(({ session, device }) => ({
        id: session.id,
        device: device ? { id: device.id, type: device.type, name: device.name } : null,
        createdAt: session.createdAt.toISOString(),
        lastUsedAt: session.lastUsedAt?.toISOString() ?? null,
        current: session.id === principal.sessionId,
      })),
    };
  }

  @Delete('sessions/:id')
  @HttpCode(204)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Sign another device out.' })
  @ApiNoContentResponse()
  async remove(@CurrentPrincipal() principal: Principal, @Param('id') id: string): Promise<void> {
    // 404 rather than 403 for someone else's session: its existence is not revealed.
    if (!(await this.sessions.revokeOwn(principal.userId, id))) {
      throw new AppError('NOT_FOUND');
    }
  }
}
