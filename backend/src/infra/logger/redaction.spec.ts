import pino from 'pino';
import { buildPinoOptions } from './logger.options';
import { REDACT_CENSOR, stripQuery } from './redaction';

function capture(): {
  logger: pino.Logger;
  lines: () => Record<string, unknown>[];
  raw: () => string;
} {
  const chunks: string[] = [];
  const logger = pino(buildPinoOptions({ logLevel: 'trace' }), {
    write: (chunk: string) => void chunks.push(chunk),
  });
  return {
    logger,
    raw: () => chunks.join(''),
    lines: () => chunks.map((chunk) => JSON.parse(chunk) as Record<string, unknown>),
  };
}

describe('log redaction', () => {
  it('redacts the authorization and cookie headers of a logged request', () => {
    const { logger, lines, raw } = capture();
    logger.info(
      {
        req: {
          id: 'req-1',
          method: 'GET',
          url: '/api/v1/health',
          headers: {
            authorization: 'Bearer super-secret-access-token',
            cookie: 'vb_admin=super-secret-cookie',
            'x-pairing-poll-token': 'vbp_super-secret-poll',
            'user-agent': 'jest',
          },
        },
      },
      'request completed',
    );

    const req = lines()[0].req as { headers: Record<string, string> };
    expect(req.headers.authorization).toBe(REDACT_CENSOR);
    expect(req.headers.cookie).toBe(REDACT_CENSOR);
    expect(req.headers['x-pairing-poll-token']).toBe(REDACT_CENSOR);
    expect(req.headers['user-agent']).toBe('jest');
    expect(raw()).not.toContain('super-secret');
  });

  it.each([
    'password',
    'otp',
    'code',
    'token',
    'accessToken',
    'refreshToken',
    'pollToken',
    'secret',
  ])('redacts "%s" at the top level and one level deep', (key) => {
    const { logger, lines, raw } = capture();
    logger.info({ [key]: 'super-secret-1', body: { [key]: 'super-secret-2', kept: 'visible' } });

    const line = lines()[0] as Record<string, unknown> & { body: Record<string, unknown> };
    expect(line[key]).toBe(REDACT_CENSOR);
    expect(line.body[key]).toBe(REDACT_CENSOR);
    expect(line.body.kept).toBe('visible');
    expect(raw()).not.toContain('super-secret');
  });

  it('logs the request URL without its query string', () => {
    const { logger, lines, raw } = capture();
    logger.info({
      req: { method: 'GET', url: '/api/v1/videos?token=super-secret&x=1', headers: {} },
    });

    expect((lines()[0].req as { url: string }).url).toBe('/api/v1/videos');
    expect(raw()).not.toContain('super-secret');
  });
});

describe('stripQuery', () => {
  it.each([
    ['/a/b', '/a/b'],
    ['/a/b?x=1', '/a/b'],
    ['/a/b#frag', '/a/b'],
    ['/a/b?x=1#frag', '/a/b'],
    ['/?', '/'],
  ])('%s -> %s', (input, expected) => {
    expect(stripQuery(input)).toBe(expected);
  });

  it('passes undefined through', () => {
    expect(stripQuery(undefined)).toBeUndefined();
  });
});
