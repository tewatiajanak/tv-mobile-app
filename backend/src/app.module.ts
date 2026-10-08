import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { APP_FILTER, APP_PIPE } from '@nestjs/core';
import { AllExceptionsFilter } from './common/filters/all-exceptions.filter';
import { RequestIdMiddleware } from './common/middleware/request-id.middleware';
import { createValidationPipe } from './common/pipes/validation.pipe';
import { ConfigModule } from './config/config.module';
import { ClockModule } from './infra/clock/clock.module';
import { LoggerModule } from './infra/logger/logger.module';
import { PrismaModule } from './infra/prisma/prisma.module';
import { RedisModule } from './infra/redis/redis.module';
import { RateLimitModule } from './common/rate-limit/rate-limit.module';
import { AuthModule } from './modules/auth/auth.module';
import { EntitlementsModule } from './modules/entitlements/entitlements.module';
import { HealthModule } from './modules/health/health.module';
import { PairingModule } from './modules/pairing/pairing.module';
import { UsersModule } from './modules/users/users.module';
import { VideosModule } from './modules/videos/videos.module';
import { TestSupportModule } from './modules/test-support/test-support.module';

@Module({
  imports: [
    ConfigModule,
    LoggerModule,
    ClockModule,
    PrismaModule,
    RedisModule,
    RateLimitModule,
    HealthModule,
    EntitlementsModule,
    AuthModule,
    UsersModule,
    PairingModule,
    VideosModule,
    ...(process.env.NODE_ENV === 'test' ? [TestSupportModule] : []),
  ],
  providers: [
    { provide: APP_FILTER, useClass: AllExceptionsFilter },
    { provide: APP_PIPE, useFactory: createValidationPipe },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestIdMiddleware).forRoutes('*path');
  }
}
