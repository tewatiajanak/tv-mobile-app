import { ApiProperty } from '@nestjs/swagger';

export class HealthResponseDto {
  @ApiProperty({ example: 'ok' })
  status!: 'ok';

  @ApiProperty({ enum: ['development', 'staging', 'production'], example: 'development' })
  env!: string;

  @ApiProperty({ description: 'Git SHA of the build, or the package version.', example: '0.1.0' })
  version!: string;

  @ApiProperty({ format: 'date-time', example: '2026-10-07T16:26:52.123Z' })
  time!: string;
}

export class ReadinessChecksDto {
  @ApiProperty({ enum: ['up', 'down'], example: 'up' })
  db!: 'up' | 'down';

  @ApiProperty({ enum: ['up', 'down', 'disabled'], example: 'disabled' })
  redis!: 'up' | 'down' | 'disabled';
}

export class ReadinessResponseDto {
  @ApiProperty({ example: 'ok' })
  status!: 'ok';

  @ApiProperty({ type: ReadinessChecksDto })
  checks!: ReadinessChecksDto;
}

class ErrorBodyDto {
  @ApiProperty({ example: 'NOT_READY' })
  code!: string;

  @ApiProperty({ example: 'The service is not ready.' })
  message!: string;

  @ApiProperty({ required: false, type: Object, example: { checks: { db: 'up', redis: 'down' } } })
  details?: Record<string, unknown>;

  @ApiProperty({ example: '0192f3c4-7a10-7c3e-9b1f-2d5e8a6c4b10' })
  requestId!: string;
}

export class ErrorEnvelopeDto {
  @ApiProperty({ type: ErrorBodyDto })
  error!: ErrorBodyDto;
}
