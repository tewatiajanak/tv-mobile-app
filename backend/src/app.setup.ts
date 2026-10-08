import type { NestExpressApplication } from '@nestjs/platform-express';
import { DocumentBuilder, OpenAPIObject, SwaggerModule } from '@nestjs/swagger';
import helmet from 'helmet';
import { Logger } from 'nestjs-pino';
import type { AppConfig } from './config/app-config';

export const GLOBAL_PREFIX = 'api/v1';
export const SWAGGER_PATH = 'api/docs';
export const BODY_LIMIT = '100kb';

export function buildOpenApiDocument(
  app: NestExpressApplication,
  config: AppConfig,
): OpenAPIObject {
  const options = new DocumentBuilder()
    .setTitle('VideoBridge API')
    .setDescription(
      'REST API for the VideoBridge phone and TV apps. Every non-2xx response uses the error envelope.',
    )
    // The package version, not the git SHA, so the exported file only changes when the API does.
    .setVersion(config.docsOnly ? '1' : config.version)
    .addBearerAuth()
    .build();
  return SwaggerModule.createDocument(app, options);
}

/** Everything main.ts, the e2e tests and the OpenAPI export must configure identically. */
export function configureApp(app: NestExpressApplication, config: AppConfig): void {
  app.useLogger(app.get(Logger));
  app.setGlobalPrefix(GLOBAL_PREFIX);
  app.set('trust proxy', config.trustProxy);
  app.disable('x-powered-by');

  app.use(
    helmet(
      config.swaggerEnabled
        ? {
            // Swagger UI needs inline script/style; this relaxation never applies in production
            // because Swagger is disabled there by default.
            contentSecurityPolicy: {
              directives: {
                defaultSrc: ["'self'"],
                scriptSrc: ["'self'", "'unsafe-inline'"],
                styleSrc: ["'self'", "'unsafe-inline'"],
                imgSrc: ["'self'", 'data:'],
                // Dev backends are reached over plain http on a LAN IP.
                upgradeInsecureRequests: null,
              },
            },
          }
        : {},
    ),
  );
  // No origins configured means no CORS headers at all: browsers are refused by default.
  app.enableCors({ origin: config.corsOrigins.length > 0 ? config.corsOrigins : false });

  app.useBodyParser('json', { limit: BODY_LIMIT });
  app.useBodyParser('urlencoded', { limit: BODY_LIMIT, extended: false });

  app.enableShutdownHooks();

  if (config.swaggerEnabled) {
    SwaggerModule.setup(SWAGGER_PATH, app, () => buildOpenApiDocument(app, config));
  }
}
