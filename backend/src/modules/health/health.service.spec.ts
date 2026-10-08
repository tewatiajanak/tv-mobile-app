import type Redis from 'ioredis';
import type { AppConfig } from '../../config/app-config';
import { FakeClock } from '../../infra/clock/clock';
import type { PrismaService } from '../../infra/prisma/prisma.service';
import { HealthService, probe } from './health.service';

const never = () => new Promise<never>(() => undefined);

function service(
  db: () => Promise<unknown>,
  redis: (() => Promise<unknown>) | null,
): HealthService {
  return new HealthService(
    { appEnv: 'development', version: 'abc1234' } as AppConfig,
    new FakeClock('2026-10-07T16:26:52.123Z'),
    redis ? ({ ping: redis } as unknown as Redis) : null,
    { $runCommandRaw: db } as unknown as PrismaService,
  );
}

describe('probe', () => {
  it('is up when the check resolves', async () => {
    await expect(probe(() => Promise.resolve('PONG'))).resolves.toBe('up');
  });

  it('is down when the check rejects or throws synchronously', async () => {
    await expect(probe(() => Promise.reject(new Error('ECONNREFUSED')))).resolves.toBe('down');
    await expect(
      probe(() => {
        throw new Error('sync');
      }),
    ).resolves.toBe('down');
  });

  it('is down when the check does not answer within the timeout', async () => {
    const started = Date.now();
    await expect(probe(never, 30)).resolves.toBe('down');
    expect(Date.now() - started).toBeLessThan(500);
  });
});

describe('HealthService', () => {
  it('liveness reports env, version and the clock time', () => {
    expect(service(never, never).liveness()).toEqual({
      status: 'ok',
      env: 'development',
      version: 'abc1234',
      time: '2026-10-07T16:26:52.123Z',
    });
  });

  it('readiness reports each dependency independently', async () => {
    const ok = () => Promise.resolve(1);
    const fail = () => Promise.reject(new Error('down'));

    await expect(service(ok, ok).readiness()).resolves.toEqual({ db: 'up', redis: 'up' });
    await expect(service(fail, ok).readiness()).resolves.toEqual({ db: 'down', redis: 'up' });
    await expect(service(ok, fail).readiness()).resolves.toEqual({ db: 'up', redis: 'down' });
  });

  it('readiness reports redis as disabled when no Redis is configured', async () => {
    const ok = () => Promise.resolve(1);

    await expect(service(ok, null).readiness()).resolves.toEqual({ db: 'up', redis: 'disabled' });
  });
});
