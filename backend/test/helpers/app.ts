import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';
import { AppModule } from '../../src/app.module';
import { configureApp } from '../../src/app.setup';
import { APP_CONFIG, AppConfig, loadConfig } from '../../src/config/app-config';

/** Boots the real application exactly as main.ts does, optionally with config overrides. */
export async function createTestApp(
  overrides: Partial<AppConfig> = {},
): Promise<NestExpressApplication> {
  const config: AppConfig = { ...loadConfig(), ...overrides };
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(APP_CONFIG)
    .useValue(config)
    .compile();

  const app = moduleRef.createNestApplication<NestExpressApplication>({ bodyParser: false });
  configureApp(app, config);
  await app.init();
  return app;
}
