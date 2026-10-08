import { Module } from '@nestjs/common';
import { LoggerModule as PinoLoggerModule } from 'nestjs-pino';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { buildLoggerParams } from './logger.options';

@Module({
  imports: [
    PinoLoggerModule.forRootAsync({
      inject: [APP_CONFIG],
      useFactory: (config: AppConfig) => buildLoggerParams(config),
    }),
  ],
})
export class LoggerModule {}
