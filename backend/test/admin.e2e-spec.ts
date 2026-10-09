import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { loadConfig } from '../src/config/app-config';
import { ADMIN_LIMITS } from '../src/modules/admin/admin.service';
import { createTestApp } from './helpers/app';
import { resetDatabase } from './helpers/db';

const PASSWORD = 'e2e-admin-password';
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
  const adminToken = async () =>
    (await http().post('/api/v1/admin/login').send({ password: PASSWORD }).expect(200)).body
      .token as string;
  const users = (token: string, query = '') =>
    http().get(`/api/v1/admin/users${query}`).set('Authorization', `Bearer ${token}`);

  beforeAll(async () => {
    app = await createTestApp({ admin: { password: PASSWORD, tokenTtlSeconds: 1800 } });
  });
  beforeEach(async () => {
    await resetDatabase(app);
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
    const wrong = () => http().post('/api/v1/admin/login').send({ password: 'wrong-password' });

    const first = await wrong().expect(401);
    expect(first.body.error).toMatchObject({ code: 'INVALID_CREDENTIALS' });
    for (let i = 1; i < ADMIN_LIMITS.loginFailuresPerIp; i += 1) {
      await wrong().expect(401);
    }
    await wrong().expect(429);
    // Even the right password waits out the lock.
    await http().post('/api/v1/admin/login').send({ password: PASSWORD }).expect(429);
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
    app = await createTestApp({ admin: { ...loadConfig().admin, password: undefined } });
  });
  afterAll(async () => {
    await app.close();
  });

  it('does not exist when no admin password is configured', async () => {
    const http = request(app.getHttpServer());
    await http.get('/admin').expect(404);
    await http.post('/api/v1/admin/login').send({ password: 'anything-at-all' }).expect(404);
    await http.get('/api/v1/admin/users').expect(404);
  });
});
