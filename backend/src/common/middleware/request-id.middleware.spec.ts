import type { IncomingMessage, ServerResponse } from 'node:http';
import { RequestIdMiddleware, getRequestId, resolveRequestId } from './request-id.middleware';

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function fakes(headers: Record<string, string | string[]> = {}, headersSent = false) {
  const req = { headers } as unknown as IncomingMessage & { id?: unknown };
  const setHeader = jest.fn();
  const res = { headersSent, setHeader } as unknown as ServerResponse;
  return { req, res, setHeader };
}

describe('RequestIdMiddleware', () => {
  it('creates a UUIDv7 when the header is absent, stores it and echoes it', () => {
    const { req, res, setHeader } = fakes();
    const next = jest.fn();

    new RequestIdMiddleware().use(req, res, next);

    expect(req.id).toMatch(UUID_V7);
    expect(getRequestId(req)).toBe(req.id);
    expect(setHeader).toHaveBeenCalledWith('X-Request-Id', req.id);
    expect(next).toHaveBeenCalledTimes(1);
  });

  it('keeps a valid incoming id', () => {
    const { req, res, setHeader } = fakes({ 'x-request-id': 'client-id_1.2' });

    expect(resolveRequestId(req, res)).toBe('client-id_1.2');
    expect(setHeader).toHaveBeenCalledWith('X-Request-Id', 'client-id_1.2');
  });

  it.each([
    ['empty', ''],
    ['too long', 'a'.repeat(129)],
    ['log injection', 'abc\n{"level":60}'],
    ['spaces', 'has space'],
  ])('replaces an unsafe incoming id (%s)', (_name, value) => {
    const { req, res } = fakes({ 'x-request-id': value });

    expect(resolveRequestId(req, res)).toMatch(UUID_V7);
  });

  it('uses the first value when the header is repeated', () => {
    const { req, res } = fakes({ 'x-request-id': ['first', 'second'] });

    expect(resolveRequestId(req, res)).toBe('first');
  });

  it('is idempotent for the same request', () => {
    const { req, res, setHeader } = fakes();

    const first = resolveRequestId(req, res);
    const second = resolveRequestId(req, res);

    expect(second).toBe(first);
    expect(setHeader).toHaveBeenCalledTimes(1);
  });

  it('does not touch headers that were already sent', () => {
    const { req, res, setHeader } = fakes({}, true);

    expect(resolveRequestId(req, res)).toMatch(UUID_V7);
    expect(setHeader).not.toHaveBeenCalled();
  });

  it('getRequestId is undefined before an id is assigned', () => {
    expect(getRequestId(fakes().req)).toBeUndefined();
  });
});
