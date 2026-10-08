import { Global, Inject, Logger, Module, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import Redis from 'ioredis';
import { APP_CONFIG, AppConfig } from '../../config/app-config';

/** Shared command connection. */
export const REDIS = Symbol('REDIS');
/** Factory for dedicated subscriber connections (a subscribed connection can't run commands). */
export const REDIS_SUBSCRIBER_FACTORY = Symbol('REDIS_SUBSCRIBER_FACTORY');

export type RedisSubscriberFactory = () => Redis | null;

/** Null when REDIS_URL is not set: Redis is optional (ADR-0007). */
export type OptionalRedis = Redis | null;

function createClient(config: AppConfig): OptionalRedis {
  if (!config.redisUrl) {
    return null;
  }
  const client = new Redis(config.redisUrl, {
    lazyConnect: true,
    // Fail fast instead of queueing while disconnected, so readiness reflects reality.
    enableOfflineQueue: false,
    maxRetriesPerRequest: 1,
  });
  // ioredis emits 'error' on every failed reconnect; without a listener that crashes the process.
  client.on('error', () => undefined);
  return client;
}

@Global()
@Module({
  providers: [
    { provide: REDIS, inject: [APP_CONFIG], useFactory: createClient },
    {
      provide: REDIS_SUBSCRIBER_FACTORY,
      inject: [APP_CONFIG],
      useFactory:
        (config: AppConfig): RedisSubscriberFactory =>
        () =>
          createClient(config),
    },
  ],
  exports: [REDIS, REDIS_SUBSCRIBER_FACTORY],
})
export class RedisModule implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RedisModule.name);

  constructor(
    @Inject(REDIS) private readonly redis: Redis | null,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  async onModuleInit(): Promise<void> {
    if (this.config.docsOnly || !this.redis) {
      return;
    }
    try {
      await this.redis.connect();
    } catch {
      // ioredis keeps retrying in the background; readiness reports the outage meanwhile.
      this.logger.error('Could not connect to Redis at startup; /health/ready reports it.');
    }
  }

  async onModuleDestroy(): Promise<void> {
    if (!this.redis) {
      return;
    }
    // QUIT needs a live connection; without one, disconnect() is what stops the reconnect timer.
    if (this.redis.status !== 'ready') {
      this.redis.disconnect();
      return;
    }
    try {
      await this.redis.quit();
    } catch {
      this.redis.disconnect();
    }
  }
}
