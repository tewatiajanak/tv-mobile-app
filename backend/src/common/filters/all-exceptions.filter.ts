import type { IncomingMessage, ServerResponse } from 'node:http';
import { ArgumentsHost, Catch, ExceptionFilter, HttpException, Logger } from '@nestjs/common';
import { HttpAdapterHost } from '@nestjs/core';
import { Prisma } from '@prisma/client';
import { AppError, ErrorEnvelope } from '../errors/app-error';
import { DEFAULT_MESSAGES, ERROR_CODES, ErrorCode } from '../errors/error-codes';
import { resolveRequestId } from '../middleware/request-id.middleware';

export interface MappedError {
  status: number;
  code: ErrorCode;
  message: string;
  details?: Record<string, unknown>;
}

const STATUS_TO_CODE: Record<number, ErrorCode> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  413: 'PAYLOAD_TOO_LARGE',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  429: 'RATE_LIMITED',
  503: 'SERVICE_UNAVAILABLE',
};

const PRISMA_TO_CODE: Record<string, ErrorCode> = {
  P2002: 'DUPLICATE', // unique constraint violated
  P2025: 'NOT_FOUND', // record to update/delete does not exist
};

function codeForStatus(status: number): ErrorCode {
  return STATUS_TO_CODE[status] ?? (status >= 500 ? 'INTERNAL' : 'BAD_REQUEST');
}

function fromCode(code: ErrorCode): MappedError {
  return { status: ERROR_CODES[code], code, message: DEFAULT_MESSAGES[code] };
}

function httpExceptionMessage(exception: HttpException, code: ErrorCode): string {
  const body = exception.getResponse();
  if (typeof body === 'string') {
    return body;
  }
  // Nest builds `{ message, error, statusCode }` when the thrower passed a message, and
  // `{ message: 'Conflict', statusCode }` when they did not. Only the former is meant for users.
  const authored =
    typeof body === 'object' && body !== null && 'error' in body && 'message' in body
      ? body.message
      : undefined;
  // "Cannot GET /path?query" (unknown route) would echo the URL back.
  if (typeof authored !== 'string' || authored.startsWith('Cannot ')) {
    return DEFAULT_MESSAGES[code];
  }
  return authored;
}

/** Errors raised by Express middleware (body-parser) carry a status but are not HttpExceptions. */
function clientStatusOf(exception: unknown): number | undefined {
  if (typeof exception !== 'object' || exception === null) {
    return undefined;
  }
  const candidate =
    'status' in exception
      ? exception.status
      : 'statusCode' in exception
        ? exception.statusCode
        : undefined;
  return typeof candidate === 'number' && candidate >= 400 && candidate < 500
    ? candidate
    : undefined;
}

/** Pure mapping from anything thrown to the client-facing error. Never exposes internals. */
export function mapException(exception: unknown): MappedError {
  if (exception instanceof AppError) {
    return {
      status: exception.httpStatus,
      code: exception.code,
      message: exception.message,
      details: exception.details,
    };
  }
  if (exception instanceof HttpException) {
    const status = exception.getStatus();
    const code = codeForStatus(status);
    if (status >= 500) {
      return { status, code, message: DEFAULT_MESSAGES[code] };
    }
    return { status, code, message: httpExceptionMessage(exception, code) };
  }
  if (exception instanceof Prisma.PrismaClientKnownRequestError) {
    const code = PRISMA_TO_CODE[exception.code];
    return fromCode(code ?? 'INTERNAL');
  }
  const clientStatus = clientStatusOf(exception);
  if (clientStatus !== undefined) {
    const code = codeForStatus(clientStatus);
    return { status: clientStatus, code, message: DEFAULT_MESSAGES[code] };
  }
  return fromCode('INTERNAL');
}

@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger(AllExceptionsFilter.name);

  constructor(private readonly adapterHost: HttpAdapterHost) {}

  catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const req = http.getRequest<IncomingMessage>();
    const res = http.getResponse<ServerResponse>();
    const mapped = mapException(exception);
    const requestId = resolveRequestId(req, res);

    if (mapped.status >= 500 && mapped.code !== 'NOT_READY') {
      // The full error stays in the logs; the client only ever sees the generic message.
      this.logger.error(
        { requestId, code: mapped.code, err: exception },
        exception instanceof Error ? exception.stack : undefined,
      );
    }

    const body: ErrorEnvelope = {
      error: {
        code: mapped.code,
        message: mapped.message,
        ...(mapped.details ? { details: mapped.details } : {}),
        requestId,
      },
    };
    this.adapterHost.httpAdapter.reply(res, body, mapped.status);
  }
}
