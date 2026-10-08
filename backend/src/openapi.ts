import 'reflect-metadata';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app.module';
import { buildOpenApiDocument, configureApp } from './app.setup';
import { APP_CONFIG, AppConfig } from './config/app-config';

/**
 * Writes docs/api/openapi.json. Runs in "docs only" mode: the app is built so every route is
 * discovered, but no database or Redis connection is opened, so it works on a machine (or CI
 * job) with nothing running.
 */
async function main(): Promise<void> {
  process.env.VB_DOCS_ONLY = '1';
  process.env.NODE_ENV = 'development';
  // Fixed placeholders, not the caller's environment: nothing is connected to, and the export
  // must not fail (or differ) because of whatever DATABASE_URL happens to be set.
  process.env.APP_ENV = 'development';
  process.env.DATABASE_URL = 'mongodb://localhost:27017/docs';
  delete process.env.REDIS_URL;
  process.env.JWT_ACCESS_SECRET = 'docs-only-placeholder-docs-only-placeholder';
  process.env.IP_HASH_SALT = 'docs-only-placeholder';
  process.env.PAIRING_SECRET = 'docs-only-placeholder-docs-only-placeholder';
  process.env.SWAGGER_ENABLED = 'true';

  const app = await NestFactory.create<NestExpressApplication>(AppModule, {
    logger: false,
    bodyParser: false,
    // With the logger off, Nest's default would exit silently on a startup error.
    abortOnError: false,
  });
  const config = app.get<AppConfig>(APP_CONFIG);
  configureApp(app, config);
  await app.init();

  const target = resolve(__dirname, '../../docs/api/openapi.json');
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, `${JSON.stringify(buildOpenApiDocument(app, config), null, 2)}\n`);
  await app.close();
  console.log(`OpenAPI written to ${target}`);
}

main().catch((error: unknown) => {
  console.error('OpenAPI export failed:', error);
  process.exit(1);
});
