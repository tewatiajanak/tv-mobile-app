import jwt from 'jsonwebtoken';
import type { AppConfig } from '../../config/app-config';
import { FakeClock } from '../../infra/clock/clock';
import { TokenService } from './token.service';

const secret = 'unit-test-secret-unit-test-secret-1234';
const config = { auth: { jwtAccessSecret: secret, accessTtlSeconds: 900 } } as AppConfig;
const claims = { userId: 'u1', deviceId: 'd1', sessionId: 's1', tokenVersion: 3 };

function code(run: () => unknown): string {
  try {
    run();
  } catch (error) {
    return (error as { code: string }).code;
  }
  return 'NO_ERROR';
}

describe('TokenService', () => {
  const clock = new FakeClock('2026-10-08T10:00:00.000Z');
  const service = new TokenService(config, clock);

  it('round-trips the claims and expires 15 minutes later', () => {
    const { token, expiresAt } = service.signAccessToken(claims);

    expect(expiresAt.toISOString()).toBe('2026-10-08T10:15:00.000Z');
    expect(service.verifyAccessToken(token)).toEqual(claims);
  });

  it('reports an expired token as TOKEN_EXPIRED', () => {
    const { token } = service.signAccessToken(claims);
    const later = new TokenService(config, new FakeClock('2026-10-08T10:15:01.000Z'));

    expect(code(() => later.verifyAccessToken(token))).toBe('TOKEN_EXPIRED');
  });

  it.each([
    ['a tampered token', () => `${service.signAccessToken(claims).token}x`],
    ['another secret', () => jwt.sign({ sub: 'u1' }, 'another-secret-another-secret-12345678')],
    [
      'the wrong audience',
      () =>
        jwt.sign({ sub: 'u1', did: 'd', sid: 's', ver: 0, typ: 'access' }, secret, {
          issuer: 'videobridge',
          audience: 'other',
        }),
    ],
    [
      'the wrong type',
      () =>
        jwt.sign({ sub: 'u1', did: 'd', sid: 's', ver: 0, typ: 'refresh' }, secret, {
          issuer: 'videobridge',
          audience: 'videobridge-app',
        }),
    ],
    [
      'an unsigned ("none") token',
      () =>
        jwt.sign({ sub: 'u1', did: 'd', sid: 's', ver: 0, typ: 'access' }, '', {
          algorithm: 'none',
          issuer: 'videobridge',
          audience: 'videobridge-app',
        }),
    ],
    ['garbage', () => 'not-a-jwt'],
  ])('rejects %s as UNAUTHENTICATED', (_name, make) => {
    expect(code(() => service.verifyAccessToken(make()))).toBe('UNAUTHENTICATED');
  });
});
