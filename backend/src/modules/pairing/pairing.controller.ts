import { Body, Controller, Get, Headers, HttpCode, Inject, Param, Post, Req } from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiProperty,
  ApiPropertyOptional,
  ApiTags,
} from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  IsNotEmpty,
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  ValidateNested,
} from 'class-validator';
import type { Request } from 'express';
import { CurrentPrincipal, Principal } from '../../common/decorators/current-principal.decorator';
import { Public } from '../../common/decorators/public.decorator';
import { clientIpHash } from '../../common/utils/client-ip';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { DeviceView, toDeviceView } from '../devices/devices.controller';
import { PairingService } from './pairing.service';

const trim = ({ value }: { value: unknown }): unknown =>
  typeof value === 'string' ? value.trim() : value;

class TvDto {
  @ApiProperty()
  @IsString()
  @Matches(/^[A-Za-z0-9-]{8,64}$/, { message: 'installId is not valid' })
  installId!: string;

  @ApiProperty({ example: 'Living Room TV' })
  @Transform(trim)
  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name!: string;

  // The apps send the same device description as at sign-in; a pairing device is always a TV.
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(20) type?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(120) manufacturer?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(120) model?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(60) osVersion?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(60) appVersion?: string;
}

class CreatePairingDto {
  @ApiProperty({ type: TvDto })
  @ValidateNested()
  @Type(() => TvDto)
  @IsNotEmpty({ message: 'tv is required' })
  tv!: TvDto;
}

class ClaimDto {
  @ApiProperty({
    example: '1234',
    description: 'The 4-digit code on the TV. Spaces and dashes are ignored.',
  })
  @IsString()
  @IsNotEmpty({ message: 'Enter the code shown on your TV' })
  @MaxLength(40)
  code!: string;
}

class ApproveDto {
  @ApiPropertyOptional({ example: 'Bedroom TV', description: 'Rename the TV while connecting it.' })
  @IsOptional()
  @Transform(trim)
  @IsString()
  @MaxLength(60)
  name?: string;
}

@ApiTags('pairing')
@Controller('pairing')
export class PairingController {
  constructor(
    private readonly pairing: PairingService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  @Public()
  @Post('sessions')
  @ApiOperation({ summary: 'TV: start pairing. Returns the code to show and a secret poll token.' })
  create(@Body() body: CreatePairingDto, @Req() req: Request) {
    return this.pairing.create(body.tv, clientIpHash(req, this.config.auth.ipHashSalt));
  }

  @Public()
  @Get('sessions/:id/status')
  @ApiOperation({ summary: 'TV: poll. After approval the sign-in is returned exactly once.' })
  status(@Param('id') id: string, @Headers('x-pairing-poll-token') pollToken?: string) {
    return this.pairing.status(id, pollToken);
  }

  @Post('claim')
  @HttpCode(200)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Phone: enter or scan the code on the TV.' })
  claim(@CurrentPrincipal() principal: Principal, @Body() body: ClaimDto) {
    return this.pairing.claim(principal.userId, principal.deviceId, body.code);
  }

  @Post(':id/approve')
  @HttpCode(200)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Phone: confirm "Connect this TV?".' })
  async approve(
    @CurrentPrincipal() principal: Principal,
    @Param('id') id: string,
    @Body() body: ApproveDto,
  ): Promise<{ device: DeviceView }> {
    return { device: toDeviceView(await this.pairing.approve(principal.userId, id, body.name)) };
  }

  @Post(':id/reject')
  @HttpCode(204)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Phone: "Not my TV".' })
  async reject(@CurrentPrincipal() principal: Principal, @Param('id') id: string): Promise<void> {
    await this.pairing.reject(principal.userId, id);
  }
}
