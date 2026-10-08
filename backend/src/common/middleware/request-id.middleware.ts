import type { IncomingMessage, ServerResponse } from 'node:http';
import { Injectable, NestMiddleware } from '@nestjs/common';
import { newId } from '../../infra/ids/ids';

export const REQUEST_ID_HEADER = 'x-request-id';

// A client-supplied id ends up in logs and responses, so only a conservative charset is accepted.
const SAFE_REQUEST_ID = /^[A-Za-z0-9._-]{1,128}$/;

type RequestWithId = IncomingMessage & { id?: unknown };

/**
 * Returns the id for this request, creating it on first use: a valid incoming `X-Request-Id`
 * is kept, anything else is replaced. The id is stored on `req.id` (which pino-http binds to
 * every log line of the request) and echoed in the response header.
 */
export function resolveRequestId(req: RequestWithId, res: ServerResponse): string {
  if (typeof req.id === 'string' && req.id.length > 0) {
    return req.id;
  }
  const incoming = req.headers[REQUEST_ID_HEADER];
  const candidate = Array.isArray(incoming) ? incoming[0] : incoming;
  const id = candidate !== undefined && SAFE_REQUEST_ID.test(candidate) ? candidate : newId();
  req.id = id;
  if (!res.headersSent) {
    res.setHeader('X-Request-Id', id);
  }
  return id;
}

/** The id assigned to a request, or undefined if none was assigned yet. */
export function getRequestId(req: RequestWithId): string | undefined {
  return typeof req.id === 'string' ? req.id : undefined;
}

@Injectable()
export class RequestIdMiddleware implements NestMiddleware {
  use(req: IncomingMessage, res: ServerResponse, next: () => void): void {
    resolveRequestId(req, res);
    next();
  }
}
