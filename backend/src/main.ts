import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app.module';
import { configureApp } from './app.setup';
import { APP_CONFIG, AppConfig, ConfigValidationError, loadConfig } from './config/app-config';

async function bootstrap(): Promise<void> {
  // Validate before Nest starts so a bad environment yields one readable list, not a DI stack trace.
  loadConfig();

  const app = await NestFactory.create<NestExpressApplication>(AppModule, {
    bufferLogs: true,
    // Registered in configureApp with an explicit size limit.
    bodyParser: false,
  });
  const config = app.get<AppConfig>(APP_CONFIG);
  configureApp(app, config);

  // 0.0.0.0 so a real phone/TV on the LAN can reach a dev machine, not just the emulator.
  await app.listen(config.port, '0.0.0.0');
}

bootstrap().catch((error: unknown) => {
  if (error instanceof ConfigValidationError) {
    console.error(error.message);
  } else {
    console.error('Backend failed to start:', error);
  }
  process.exit(1);
});
