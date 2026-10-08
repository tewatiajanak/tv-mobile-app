import { Inject, Injectable } from '@nestjs/common';
import Redis from 'ioredis';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { REDIS } from '../../infra/redis/redis.module';

export type CheckStatus = 'up' | 'down';

export interface ReadinessChecks {
  db: CheckStatus;
  /** 'disabled' when the backend is configured without Redis; that does not block readiness. */
  redis: CheckStatus | 'disabled';
}

export const CHECK_TIMEOUT_MS = 1_000;

/** Resolves 'up' only if the probe succeeds within the timeout; never rejects. */
export async function probe(
  run: () => Promise<unknown>,
  timeoutMs: number = CHECK_TIMEOUT_MS,
): Promise<CheckStatus> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<CheckStatus>((resolve) => {
    timer = setTimeout(() => resolve('down'), timeoutMs);
  });
  const attempt = Promise.resolve()
    .then(run)
    .then(
      (): CheckStatus => 'up',
      (): CheckStatus => 'down',
    );
  try {
    return await Promise.race([attempt, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

@Injectable()
export class HealthService {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    @Inject(REDIS) private readonly redis: Redis | null,
    private readonly prisma: PrismaService,
  ) {}

  /** Liveness: no dependencies, so it stays 200 while MongoDB or Redis is down. */
  liveness(): { status: 'ok'; env: string; version: string; time: string } {
    return {
      status: 'ok',
      env: this.config.appEnv,
      version: this.config.version,
      time: this.clock.now().toISOString(),
    };
  }

  async readiness(): Promise<ReadinessChecks> {
    const redisClient = this.redis;
    const [db, redis] = await Promise.all([
      probe(() => this.prisma.$runCommandRaw({ ping: 1 })),
      redisClient ? probe(() => redisClient.ping()) : Promise.resolve('disabled' as const),
    ]);
    return { db, redis };
  }
}
