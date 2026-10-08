import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { PrismaService } from '../src/infra/prisma/prisma.service';
import { createTestApp } from './helpers/app';
import { resetDatabase } from './helpers/db';

const phoneDevice = {
  installId: '0192aaaa-0000-7000-8000-000000000001',
  type: 'PHONE',
  name: 'Test Phone',
};
const tvDevice = {
  installId: '0192bbbb-0000-7000-8000-000000000002',
  type: 'ANDROID_TV',
  name: 'Test TV',
};

describe('Authentication (e2e)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());

  const register = (overrides: Record<string, unknown> = {}) =>
    http()
      .post('/api/v1/auth/register')
      .send({
        name: 'Janak',
        phone: '9876543210',
        password: '1',
        device: phoneDevice,
        ...overrides,
      });
  const login = (overrides: Record<string, unknown> = {}) =>
    http()
      .post('/api/v1/auth/login')
      .send({ phone: '9876543210', password: '1', device: phoneDevice, ...overrides });
  const me = (token: string) =>
    http().get('/api/v1/users/me').set('Authorization', `Bearer ${token}`);
  const refresh = (refreshToken: string) =>
    http().post('/api/v1/auth/refresh').send({ refreshToken });

  beforeAll(async () => {
    app = await createTestApp({
      auth: {
        jwtAccessSecret: 'e2e-test-secret-e2e-test-secret-e2e-1234',
        accessTtlSeconds: 900,
        refreshTtlDaysPhone: 180,
        refreshTtlDaysTv: 180,
        // 0 so "reuse of a replaced token" can be tested without waiting out the grace window.
        refreshReuseGraceSeconds: 0,
        ipHashSalt: 'e2e-test-salt-1234',
        defaultPhoneRegion: 'IN',
      },
    });
    prisma = app.get(PrismaService);
  });

  beforeEach(async () => {
    await resetDatabase(app);
  });

  afterAll(async () => {
    await app.close();
  });

  describe('register', () => {
    it('creates the account with a one-character password, signs in, and stores only a hash', async () => {
      const res = await register().expect(201);

      expect(res.body).toEqual({
        accessToken: expect.any(String),
        accessTokenExpiresAt: expect.any(String),
        refreshToken: expect.stringMatching(/^vbr_/),
        refreshTokenExpiresAt: expect.any(String),
        user: {
          id: expect.any(String),
          phoneMasked: '+91******3210',
          displayName: 'Janak',
          createdAt: expect.any(String),
        },
        device: { id: expect.any(String), type: 'PHONE', name: 'Test Phone' },
        session: { id: expect.any(String) },
      });
      const stored = await prisma.user.findUniqueOrThrow({ where: { phoneE164: '+919876543210' } });
      expect(stored.passwordHash).toMatch(/^scrypt\$/);
      expect(stored.passwordHash).not.toBe('1');
      expect(JSON.stringify(res.body)).not.toContain('passwordHash');

      const profile = await me(res.body.accessToken).expect(200);
      expect(profile.body.displayName).toBe('Janak');
    });

    it.each([
      ['name', { name: '' }],
      ['name', { name: '   ' }],
      ['phone', { phone: '' }],
      ['password', { password: '' }],
      ['name', { name: undefined }],
      ['password', { password: undefined }],
    ])('requires %s', async (field, overrides) => {
      const res = await register(overrides).expect(400);

      expect(res.body.error.code).toBe('VALIDATION_FAILED');
      expect(Object.keys(res.body.error.details.fields)).toContain(field);
    });

    it('rejects an invalid mobile number', async () => {
      const res = await register({ phone: '12345' }).expect(400);

      expect(res.body.error).toMatchObject({
        code: 'VALIDATION_FAILED',
        details: { fields: { phone: ['INVALID_PHONE'] } },
      });
    });

    it('refuses a number that already has an account, however it is written', async () => {
      await register().expect(201);

      const res = await register({ phone: '+91 98765 43210', name: 'Other' }).expect(409);

      expect(res.body.error.code).toBe('PHONE_ALREADY_REGISTERED');
    });
  });

  describe('login', () => {
    beforeEach(async () => {
      await register().expect(201);
    });

    it('signs in with the right password', async () => {
      const res = await login().expect(200);

      expect(res.body.user.displayName).toBe('Janak');
      await me(res.body.accessToken).expect(200);
    });

    it('gives the same error for a wrong password and an unknown number', async () => {
      const wrong = await login({ password: '2' }).expect(401);
      const unknown = await login({ phone: '9123456789' }).expect(401);

      expect(wrong.body.error.code).toBe('INVALID_CREDENTIALS');
      expect({ ...unknown.body.error, requestId: '' }).toEqual({
        ...wrong.body.error,
        requestId: '',
      });
    });

    it('locks the number after 10 wrong passwords, even for the right one', async () => {
      for (let attempt = 0; attempt < 10; attempt += 1) {
        await login({ password: 'wrong' }).expect(401);
      }

      const res = await login().expect(429);

      expect(res.body.error.code).toBe('RATE_LIMITED');
      expect(res.body.error.details.retryAfterSeconds).toBeGreaterThan(0);
    });

    it('keeps one session per device: signing in again replaces the old one', async () => {
      const first = await login().expect(200);
      const second = await login().expect(200);

      await me(first.body.accessToken).expect(401);
      await me(second.body.accessToken).expect(200);
    });
  });

  describe('staying signed in (refresh)', () => {
    it('rotates the pair; the new access token works', async () => {
      const signedIn = await register().expect(201);

      const res = await refresh(signedIn.body.refreshToken).expect(200);

      expect(res.body.refreshToken).not.toBe(signedIn.body.refreshToken);
      await me(res.body.accessToken).expect(200);
    });

    it('revokes the session when a replaced refresh token is used again', async () => {
      const signedIn = await register().expect(201);
      const rotated = await refresh(signedIn.body.refreshToken).expect(200);

      const reuse = await refresh(signedIn.body.refreshToken).expect(401);

      expect(reuse.body.error.code).toBe('SESSION_REVOKED');
      // The legitimate holder of the newest token is signed out too.
      await refresh(rotated.body.refreshToken).expect(401);
      await me(rotated.body.accessToken).expect(401);
    });

    it('rejects an unknown refresh token', async () => {
      const res = await refresh('vbr_not-a-real-token').expect(401);

      expect(res.body.error.code).toBe('REFRESH_INVALID');
    });
  });

  describe('sessions', () => {
    it('logout ends the session at once, for both tokens', async () => {
      const signedIn = await register().expect(201);
      const auth = `Bearer ${signedIn.body.accessToken}`;

      await http().post('/api/v1/auth/logout').set('Authorization', auth).expect(204);

      await me(signedIn.body.accessToken).expect(401);
      await refresh(signedIn.body.refreshToken).expect(401);
    });

    it('lists phone and TV sessions, and removing the TV session signs the TV out', async () => {
      const phone = await register().expect(201);
      const tv = await login({ device: tvDevice }).expect(200);

      const list = await http()
        .get('/api/v1/auth/sessions')
        .set('Authorization', `Bearer ${phone.body.accessToken}`)
        .expect(200);
      expect(list.body.items).toHaveLength(2);
      expect(list.body.items.find((item: { current: boolean }) => item.current).device.type).toBe(
        'PHONE',
      );

      await http()
        .delete(`/api/v1/auth/sessions/${tv.body.session.id}`)
        .set('Authorization', `Bearer ${phone.body.accessToken}`)
        .expect(204);

      await me(tv.body.accessToken).expect(401);
      await me(phone.body.accessToken).expect(200);
    });

    it('logout-all signs every device out', async () => {
      const phone = await register().expect(201);
      const tv = await login({ device: tvDevice }).expect(200);

      await http()
        .post('/api/v1/auth/logout-all')
        .set('Authorization', `Bearer ${phone.body.accessToken}`)
        .expect(204);

      await me(phone.body.accessToken).expect(401);
      await me(tv.body.accessToken).expect(401);
    });

    it("cannot remove another user's session (404)", async () => {
      const a = await register().expect(201);
      const b = await register({ phone: '9123456780', name: 'B' }).expect(201);

      await http()
        .delete(`/api/v1/auth/sessions/${a.body.session.id}`)
        .set('Authorization', `Bearer ${b.body.accessToken}`)
        .expect(404);
      await me(a.body.accessToken).expect(200);
    });
  });

  describe('protected routes', () => {
    it.each([
      ['no token', undefined],
      ['a malformed token', 'Bearer not.a.jwt'],
      ['the wrong scheme', 'Basic abc'],
    ])('rejects %s with UNAUTHENTICATED', async (_name, header) => {
      const req = http().get('/api/v1/users/me');
      const res = await (header ? req.set('Authorization', header) : req).expect(401);

      expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    it('lets the user change their name', async () => {
      const signedIn = await register().expect(201);

      const res = await http()
        .patch('/api/v1/users/me')
        .set('Authorization', `Bearer ${signedIn.body.accessToken}`)
        .send({ displayName: '  Janak T  ' })
        .expect(200);

      expect(res.body.displayName).toBe('Janak T');
    });
  });
});
