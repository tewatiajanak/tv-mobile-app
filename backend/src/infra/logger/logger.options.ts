import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Params } from 'nestjs-pino';
import type { AppConfig } from '../../config/app-config';
import { resolveRequestId } from '../../common/middleware/request-id.middleware';
import { REDACT_CENSOR, REDACT_PATHS, stripQuery } from './redaction';

interface SerializedRequest {
  id?: unknown;
  method?: string;
  url?: string;
  headers?: Record<string, unknown>;
}

/** Base pino options, shared with tests so they exercise the real redaction config. */
export function buildPinoOptions(config: Pick<AppConfig, 'logLevel'>) {
  return {
    level: config.logLevel,
    redact: { paths: REDACT_PATHS, censor: REDACT_CENSOR },
    serializers: {
      req: (req: SerializedRequest): SerializedRequest => ({
        id: req.id,
        method: req.method,
        url: stripQuery(req.url),
        headers: req.headers,
      }),
    },
  };
}

export function buildLoggerParams(config: AppConfig): Params {
  // Pretty output is for a developer's terminal only; staging/production (and tests) emit JSON.
  const pretty = config.appEnv === 'development' && config.nodeEnv === 'development';
  return {
    pinoHttp: {
      ...buildPinoOptions(config),
      enabled: !config.docsOnly,
      genReqId: (req: IncomingMessage, res: ServerResponse) => resolveRequestId(req, res),
      customProps: () => ({ env: config.appEnv }),
      transport: pretty
        ? { target: 'pino-pretty', options: { singleLine: true, translateTime: 'SYS:HH:MM:ss.l' } }
        : undefined,
    },
  };
}
