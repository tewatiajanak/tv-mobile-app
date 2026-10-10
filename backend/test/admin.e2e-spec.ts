import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { loadConfig } from '../src/config/app-config';
import { AdminMailer } from '../src/modules/admin/admin.mailer';
import { ADMIN_LIMITS } from '../src/modules/admin/admin.service';
import { PrismaService } from '../src/infra/prisma/prisma.service';
import { createTestApp } from './helpers/app';
import { resetDatabase } from './helpers/db';

const PHONE = '9000000001';
const PASSWORD = 'e2e-admin-password';
const ADMIN = {
  phone: '+919000000001',
  password: PASSWORD,
  email: 'owner@example.com',
  resendApiKey: 're_test_key',
  emailFrom: 'Dekho <onboarding@resend.dev>',
  tokenTtlSeconds: 1800,
};
const device = {
  installId: '0192aaaa-0000-7000-8000-000000000001',
  type: 'PHONE',
  name: 'Test Phone',
};

describe('Admin users page (e2e)', () => {
  let app: NestExpressApplication;
  const http = () => request(app.getHttpServer());

  const register = async (name: string, phone: string) =>
    (
      await http()
        .post('/api/v1/auth/register')
        .send({ name, phone, password: '1', device })
        .expect(201)
    ).body.accessToken as string;
  const login = (password: string, phone = PHONE) =>
    http().post('/api/v1/admin/login').send({ phone, password });
  const adminToken = async (password = PASSWORD) =>
    (await login(password).expect(200)).body.token as string;
  /** Asks for a reset code and returns the one that would have been emailed. */
  const emailedCode = async (phone = PHONE) => {
    const send = jest.spyOn(app.get(AdminMailer), 'send').mockResolvedValue(true);
    await http().post('/api/v1/admin/forgot').send({ phone }).expect(204);
    const text = send.mock.calls[0]?.[1];
    send.mockRestore();
    return text ? /\b(\d{6})\b/.exec(text)?.[1] : undefined;
  };
  const users = (token: string, query = '') =>
    http().get(`/api/v1/admin/users${query}`).set('Authorization', `Bearer ${token}`);

  beforeAll(async () => {
    app = await createTestApp({ admin: ADMIN });
  });
  beforeEach(async () => {
    await resetDatabase(app);
    await app.get(PrismaService).adminCredential.deleteMany();
  });
  afterAll(async () => {
    await app.close();
  });

  it('lists every user with name and full mobile number, newest first', async () => {
    await register('Janak', '9876543210');
    await register('Meera', '9876543211');

    const res = await users(await adminToken()).expect(200);

    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.total).toBe(2);
    expect(res.body.items.map((u: { name: string; phone: string }) => [u.name, u.phone])).toEqual([
      ['Meera', '+919876543211'],
      ['Janak', '+919876543210'],
    ]);
    expect(res.body.items[0]).toMatchObject({ status: 'ACTIVE' });
    expect(JSON.stringify(res.body)).not.toContain('scrypt');
    expect(res.body.items[0]).not.toHaveProperty('passwordHash');
  });

  it('searches by name or by part of the number, and pages', async () => {
    await register('Janak', '9876543210');
    await register('Meera', '9876543211');
    await register('Jaya', '9123456780');
    const token = await adminToken();

    expect((await users(token, '?q=ja').expect(200)).body.total).toBe(2);
    expect((await users(token, '?q=3211').expect(200)).body.items[0].name).toBe('Meera');
    expect((await users(token, '?q=nobody').expect(200)).body).toEqual({ total: 0, items: [] });

    const second = await users(token, '?limit=2&page=2').expect(200);
    expect(second.body.total).toBe(3);
    expect(second.body.items).toHaveLength(1);
    await users(token, '?limit=1000').expect(400);
  });

  it('refuses everyone without an admin token, including a signed-in user', async () => {
    const userToken = await register('Janak', '9876543210');

    await http().get('/api/v1/admin/users').expect(401);
    await users(userToken).expect(401);
    await users('not-a-token').expect(401);
    // And an admin token is not an app token.
    const admin = await adminToken();
    await http().get('/api/v1/videos').set('Authorization', `Bearer ${admin}`).expect(401);
  });

  it('rejects a wrong password and locks the address after repeated failures', async () => {
    const wrong = () => login('wrong-password');

    const first = await wrong().expect(401);
    expect(first.body.error).toMatchObject({ code: 'INVALID_CREDENTIALS' });
    for (let i = 1; i < ADMIN_LIMITS.loginFailuresPerIp; i += 1) {
      await wrong().expect(401);
    }
    await wrong().expect(429);
    // Even the right password waits out the lock.
    await login(PASSWORD).expect(429);
  });

  it('signs in only with the admin number, and says the same for a wrong number or password', async () => {
    await register('Meera', '9876543211');

    const otherNumber = await login(PASSWORD, '9876543211').expect(401);
    const wrongPassword = await login('wrong-password').expect(401);
    expect(otherNumber.body.error.message).toBe(wrongPassword.body.error.message);
    // A user's own app password does not open the admin page either.
    await login('1', '9876543211').expect(401);
    await login(PASSWORD, '+91 90000 00001').expect(200);
  });

  it('changes the password, after which the first password and older tokens stop working', async () => {
    const old = await adminToken();
    const change = (token: string, currentPassword: string, newPassword: string) =>
      http()
        .post('/api/v1/admin/password')
        .set('Authorization', `Bearer ${token}`)
        .send({ currentPassword, newPassword });

    await http().post('/api/v1/admin/password').send({}).expect(401);
    await change(old, 'not-the-password', 'new-password-1').expect(401);
    await change(old, PASSWORD, 'short').expect(400);
    const fresh = (await change(old, PASSWORD, 'new-password-1').expect(200)).body.token as string;

    await users(old).expect(401);
    await users(fresh).expect(200);
    await login(PASSWORD).expect(401);
    await users(await adminToken('new-password-1')).expect(200);
    const stored = await app.get(PrismaService).adminCredential.findMany();
    expect(JSON.stringify(stored)).not.toContain('new-password-1');
  });

  it('resets the password with an emailed code, once', async () => {
    const old = await adminToken();
    const code = await emailedCode();
    expect(code).toMatch(/^\d{6}$/);
    const reset = (attempt: string | undefined, phone = PHONE) =>
      http()
        .post('/api/v1/admin/reset')
        .send({ phone, code: attempt, newPassword: 'after-reset-1' });
    const wrong = code === '000000' ? '000001' : '000000';

    await reset(wrong).expect(401);
    await reset(code, '9876543211').expect(401);
    // The first password still works until the reset completes.
    await login(PASSWORD).expect(200);
    const res = await reset(code).expect(200);

    await users(res.body.token as string).expect(200);
    await users(old).expect(401);
    await login(PASSWORD).expect(401);
    await login('after-reset-1').expect(200);
    await reset(code).expect(401);
  });

  it('emails no code for any other number, and answers the same', async () => {
    await register('Meera', '9876543211');
    expect(await emailedCode('9876543211')).toBeUndefined();
    expect(await app.get(PrismaService).adminCredential.count()).toBe(0);
  });

  it('expires a code after ten minutes and gives up after too many wrong tries', async () => {
    const reset = (attempt: string | undefined) =>
      http()
        .post('/api/v1/admin/reset')
        .send({ phone: PHONE, code: attempt, newPassword: 'after-reset-1' });

    const expired = await emailedCode();
    await app.get(PrismaService).adminCredential.updateMany({
      data: { otpExpiresAt: new Date(Date.now() - 1000) },
    });
    await reset(expired).expect(401);

    const code = await emailedCode();
    const wrong = code === '000000' ? '000001' : '000000';
    for (let i = 0; i < ADMIN_LIMITS.otpAttempts; i += 1) {
      await reset(wrong).expect(401);
    }
    await reset(code).expect(401);
  });

  it('serves the page with a script policy that only allows its own nonce', async () => {
    const res = await http().get('/admin').expect(200);

    expect(res.headers['content-type']).toContain('text/html');
    const nonce = /script-src 'nonce-([\w-]+)'/.exec(res.headers['content-security-policy'])?.[1];
    expect(nonce).toBeTruthy();
    expect(res.text).toContain(`<script nonce="${nonce}">`);
    expect(res.headers['content-security-policy']).toContain("default-src 'none'");
    expect(res.headers['x-robots-tag']).toContain('noindex');
    expect(res.text).not.toContain(PASSWORD);
  });
});

describe('Admin switched off (e2e)', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    app = await createTestApp({ admin: { ...loadConfig().admin, phone: undefined } });
  });
  afterAll(async () => {
    await app.close();
  });

  it('does not exist when no admin password is configured', async () => {
    const http = request(app.getHttpServer());
    await http.get('/admin').expect(404);
    await http
      .post('/api/v1/admin/login')
      .send({ phone: '9000000001', password: 'anything-at-all' })
      .expect(404);
    await http.post('/api/v1/admin/forgot').send({ phone: '9000000001' }).expect(404);
    await http.get('/api/v1/admin/users').expect(404);
  });
});
