import { Controller, Get } from '@nestjs/common';
import {
  ApiOkResponse,
  ApiOperation,
  ApiServiceUnavailableResponse,
  ApiTags,
} from '@nestjs/swagger';
import { Public } from '../../common/decorators/public.decorator';
import { AppError } from '../../common/errors/app-error';
import { ErrorEnvelopeDto, HealthResponseDto, ReadinessResponseDto } from './dto/health.dto';
import { HealthService } from './health.service';

@ApiTags('health')
@Public()
@Controller('health')
export class HealthController {
  constructor(private readonly health: HealthService) {}

  @Get()
  @ApiOperation({ summary: 'Liveness: the process is up. Checks no dependencies.' })
  @ApiOkResponse({ type: HealthResponseDto })
  liveness(): HealthResponseDto {
    return this.health.liveness();
  }

  @Get('ready')
  @ApiOperation({ summary: 'Readiness: MongoDB (and Redis, when configured) is reachable.' })
  @ApiOkResponse({ type: ReadinessResponseDto })
  @ApiServiceUnavailableResponse({
    type: ErrorEnvelopeDto,
    description: 'NOT_READY. `details.checks` says which dependency is down.',
  })
  async readiness(): Promise<ReadinessResponseDto> {
    const checks = await this.health.readiness();
    if (checks.db !== 'up' || checks.redis === 'down') {
      throw new AppError('NOT_READY', undefined, { checks });
    }
    return { status: 'ok', checks };
  }
}
