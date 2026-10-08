import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  IsEnum,
  IsNotEmpty,
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  MinLength,
  ValidateNested,
} from 'class-validator';

const trim = ({ value }: { value: unknown }): unknown =>
  typeof value === 'string' ? value.trim() : value;

export class DeviceDto {
  @ApiProperty({ description: 'Random id created by the app on first launch.' })
  @IsString()
  @Matches(/^[A-Za-z0-9-]{8,64}$/, { message: 'installId is not valid' })
  installId!: string;

  @ApiProperty({ enum: ['PHONE', 'ANDROID_TV'] })
  @IsEnum(['PHONE', 'ANDROID_TV'], { message: 'type must be PHONE or ANDROID_TV' })
  type!: 'PHONE' | 'ANDROID_TV';

  @ApiProperty({ example: 'OnePlus 13' })
  @Transform(trim)
  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name!: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(120)
  manufacturer?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(120)
  model?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  osVersion?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  appVersion?: string;
}

export class LoginDto {
  @ApiProperty({
    example: '9876543210',
    description: 'Mobile number; +91 is assumed without a country code.',
  })
  @Transform(trim)
  @IsString()
  @IsNotEmpty({ message: 'Enter your mobile number' })
  @MaxLength(25)
  phone!: string;

  // Deliberately no strength rules (owner's decision, ADR-0008): one character is enough.
  @ApiProperty({ description: 'Any password of 1 to 200 characters.' })
  @IsString()
  @MinLength(1, { message: 'Enter a password' })
  @MaxLength(200, { message: 'Password is too long' })
  password!: string;

  @ApiProperty({ type: DeviceDto })
  @ValidateNested()
  @Type(() => DeviceDto)
  @IsNotEmpty({ message: 'device is required' })
  device!: DeviceDto;
}

export class RegisterDto extends LoginDto {
  @ApiProperty({ example: 'Janak' })
  @Transform(trim)
  @IsString()
  @IsNotEmpty({ message: 'Enter your name' })
  @MaxLength(60, { message: 'Name is too long' })
  name!: string;
}

export class RefreshDto {
  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  @MaxLength(200)
  refreshToken!: string;
}

export class UserDto {
  @ApiProperty() id!: string;
  @ApiProperty({ example: '+91******3210' }) phoneMasked!: string;
  @ApiProperty() displayName!: string;
  @ApiProperty({ format: 'date-time' }) createdAt!: string;
}

export class DeviceSummaryDto {
  @ApiProperty() id!: string;
  @ApiProperty({ enum: ['PHONE', 'ANDROID_TV'] }) type!: string;
  @ApiProperty() name!: string;
}

export class TokenPairDto {
  @ApiProperty() accessToken!: string;
  @ApiProperty({ format: 'date-time' }) accessTokenExpiresAt!: string;
  @ApiProperty() refreshToken!: string;
  @ApiProperty({ format: 'date-time' }) refreshTokenExpiresAt!: string;
}

export class SessionRefDto {
  @ApiProperty() id!: string;
}

export class AuthResponseDto extends TokenPairDto {
  @ApiProperty({ type: UserDto }) user!: UserDto;
  @ApiProperty({ type: DeviceSummaryDto }) device!: DeviceSummaryDto;
  @ApiProperty({ type: SessionRefDto }) session!: SessionRefDto;
}

export class SessionItemDto {
  @ApiProperty() id!: string;
  @ApiProperty({ type: DeviceSummaryDto, nullable: true }) device!: DeviceSummaryDto | null;
  @ApiProperty({ format: 'date-time' }) createdAt!: string;
  @ApiProperty({ format: 'date-time', nullable: true }) lastUsedAt!: string | null;
  @ApiProperty() current!: boolean;
}

export class SessionListDto {
  @ApiProperty({ type: [SessionItemDto] }) items!: SessionItemDto[];
}
