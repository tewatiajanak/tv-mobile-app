import {
  ArgumentsHost,
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  InternalServerErrorException,
  NotFoundException,
  PayloadTooLargeException,
  UnauthorizedException,
} from '@nestjs/common';
import { HttpAdapterHost } from '@nestjs/core';
import { Prisma } from '@prisma/client';
import { AppError } from '../errors/app-error';
import { AllExceptionsFilter, mapException } from './all-exceptions.filter';

function prismaError(code: string): Prisma.PrismaClientKnownRequestError {
  return new Prisma.PrismaClientKnownRequestError(
    'Unique constraint failed on the fields: (`phone_e164`) in SELECT * FROM "users"',
    { code, clientVersion: 'test' },
  );
}

describe('mapException', () => {
  it('maps an AppError with its details', () => {
    const error = new AppError('ENTITLEMENT_LIMIT', 'Your FREE plan allows 1 TV.', {
      entitlement: 'max_tv_devices',
      limit: 1,
      current: 1,
    });

    expect(mapException(error)).toEqual({
      status: 403,
      code: 'ENTITLEMENT_LIMIT',
      message: 'Your FREE plan allows 1 TV.',
      details: { entitlement: 'max_tv_devices', limit: 1, current: 1 },
    });
  });

  it('uses the registry status and default message for an AppError', () => {
    expect(mapException(new AppError('RATE_LIMITED'))).toEqual({
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Too many requests. Please try again later.',
      details: undefined,
    });
  });

  it.each([
    [new BadRequestException('Bad thing'), 400, 'BAD_REQUEST', 'Bad thing'],
    [new UnauthorizedException(), 401, 'UNAUTHENTICATED', 'Authentication is required.'],
    [new ForbiddenException('No.'), 403, 'FORBIDDEN', 'No.'],
    [new NotFoundException('Video not found'), 404, 'NOT_FOUND', 'Video not found'],
    [new ConflictException(), 409, 'CONFLICT', 'The request conflicts with the current state.'],
    [new PayloadTooLargeException(), 413, 'PAYLOAD_TOO_LARGE', 'The request is too large.'],
    [new HttpException('teapot', 418), 418, 'BAD_REQUEST', 'teapot'],
  ])('maps Nest HttpException %#', (exception, status, code, message) => {
    expect(mapException(exception)).toEqual({ status, code, message });
  });

  it('does not echo the URL of an unknown route', () => {
    const mapped = mapException(new NotFoundException('Cannot GET /api/v1/nope?token=secret'));

    expect(mapped).toEqual({ status: 404, code: 'NOT_FOUND', message: 'Not found.' });
  });

  it('never exposes the message of a 5xx HttpException', () => {
    const mapped = mapException(new InternalServerErrorException('db password is hunter2'));

    expect(mapped).toEqual({
      status: 500,
      code: 'INTERNAL',
      message: 'Something went wrong. Please try again.',
    });
  });

  it('maps Prisma P2002 to DUPLICATE and P2025 to NOT_FOUND without leaking SQL', () => {
    expect(mapException(prismaError('P2002'))).toEqual({
      status: 409,
      code: 'DUPLICATE',
      message: 'This already exists.',
    });
    expect(mapException(prismaError('P2025'))).toEqual({
      status: 404,
      code: 'NOT_FOUND',
      message: 'Not found.',
    });
  });

  it('maps other Prisma errors to INTERNAL', () => {
    expect(mapException(prismaError('P2003')).code).toBe('INTERNAL');
  });

  it('maps body-parser style errors by their status', () => {
    const tooLarge = Object.assign(new Error('request entity too large'), {
      status: 413,
      type: 'entity.too.large',
    });
    const malformed = Object.assign(new SyntaxError('Unexpected token } in JSON at position 9'), {
      statusCode: 400,
      type: 'entity.parse.failed',
    });

    expect(mapException(tooLarge)).toEqual({
      status: 413,
      code: 'PAYLOAD_TOO_LARGE',
      message: 'The request is too large.',
    });
    expect(mapException(malformed)).toEqual({
      status: 400,
      code: 'BAD_REQUEST',
      message: 'The request could not be understood.',
    });
  });

  it.each([
    ['an Error', new Error('connect ECONNREFUSED 10.0.0.5:5432')],
    ['a string', 'boom'],
    ['null', null],
    ['an object with a 5xx status', { status: 502, message: 'upstream db-internal-1 failed' }],
  ])('maps %s to a generic INTERNAL', (_name, thrown) => {
    expect(mapException(thrown)).toEqual({
      status: 500,
      code: 'INTERNAL',
      message: 'Something went wrong. Please try again.',
    });
  });
});

describe('AllExceptionsFilter', () => {
  function run(exception: unknown, headers: Record<string, string> = {}) {
    const reply = jest.fn();
    const req = { headers };
    const res = { headersSent: false, setHeader: jest.fn() };
    const host = {
      switchToHttp: () => ({ getRequest: () => req, getResponse: () => res }),
    } as unknown as ArgumentsHost;
    const filter = new AllExceptionsFilter({
      httpAdapter: { reply },
    } as unknown as HttpAdapterHost);
    const logError = jest.spyOn(filter['logger'], 'error').mockImplementation(() => undefined);

    filter.catch(exception, host);

    const [, body, status] = reply.mock.calls[0] as [
      unknown,
      { error: Record<string, unknown> },
      number,
    ];
    return { body, status, res, logError };
  }

  it('replies with the envelope, the request id and the echo header', () => {
    const { body, status, res, logError } = run(new AppError('NOT_FOUND'), {
      'x-request-id': 'req-123',
    });

    expect(status).toBe(404);
    expect(body).toEqual({
      error: { code: 'NOT_FOUND', message: 'Not found.', requestId: 'req-123' },
    });
    expect(res.setHeader).toHaveBeenCalledWith('X-Request-Id', 'req-123');
    expect(logError).not.toHaveBeenCalled();
  });

  it('includes details only when present', () => {
    const { body } = run(new AppError('VALIDATION_FAILED', undefined, { fields: { name: ['x'] } }));

    expect(body.error.details).toEqual({ fields: { name: ['x'] } });
  });

  it('logs unknown errors with their stack but returns a generic message', () => {
    const { body, status, logError } = run(new Error('SELECT * FROM users failed at db-internal'));

    expect(status).toBe(500);
    expect(body.error.code).toBe('INTERNAL');
    expect(JSON.stringify(body)).not.toContain('SELECT');
    expect(body.error.requestId).toEqual(expect.any(String));
    expect(logError).toHaveBeenCalledTimes(1);
    expect(logError.mock.calls[0][1]).toContain('SELECT * FROM users failed');
  });

  it('does not log NOT_READY as an error', () => {
    const { status, logError } = run(new AppError('NOT_READY'));

    expect(status).toBe(503);
    expect(logError).not.toHaveBeenCalled();
  });
});
