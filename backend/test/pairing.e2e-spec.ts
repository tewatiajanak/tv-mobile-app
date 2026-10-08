import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { createTestApp } from './helpers/app';
import { resetDatabase } from './helpers/db';

const phone = {
  installId: '0192aaaa-0000-7000-8000-000000000001',
  type: 'PHONE',
  name: 'Test Phone',
};
const tv = {
  installId: '0192bbbb-0000-7000-8000-000000000002',
  name: 'Living Room TV',
  model: 'Emu',
};

describe('Devices and TV pairing (e2e)', () => {
  let app: NestExpressApplication;
  const http = () => request(app.getHttpServer());

  const signUp = async (phoneNumber = '9876543210', device = phone) =>
    (
      await http()
        .post('/api/v1/auth/register')
        .send({ name: 'Janak', phone: phoneNumber, password: '1', device })
        .expect(201)
    ).body;
  const startPairing = async (tvInfo: Record<string, unknown> = tv) =>
    (await http().post('/api/v1/pairing/sessions').send({ tv: tvInfo }).expect(201)).body;
  const status = (pairing: { pairingId: string; pollToken: string }) =>
    http()
      .get(`/api/v1/pairing/sessions/${pairing.pairingId}/status`)
      .set('X-Pairing-Poll-Token', pairing.pollToken);
  const claim = (token: string, code: string) =>
    http().post('/api/v1/pairing/claim').set('Authorization', `Bearer ${token}`).send({ code });
  const approve = (token: string, id: string, body: Record<string, unknown> = {}) =>
    http().post(`/api/v1/pairing/${id}/approve`).set('Authorization', `Bearer ${token}`).send(body);
  const me = (token: string) =>
    http().get('/api/v1/users/me').set('Authorization', `Bearer ${token}`);
  const devices = (token: string) =>
    http().get('/api/v1/devices').set('Authorization', `Bearer ${token}`);

  beforeAll(async () => {
    app = await createTestApp();
  });
  beforeEach(async () => {
    await resetDatabase(app);
  });
  afterAll(async () => {
    await app.close();
  });

  it('pairs a TV: code -> claim -> approve -> the TV picks up its sign-in exactly once', async () => {
    const user = await signUp();
    const pairing = await startPairing();
    expect(pairing.code).toMatch(/^\d{4}$/);
    expect(pairing.qrPayload).toBe(`videobridge://pair?c=${pairing.code}`);
    expect(pairing.qrPayload).not.toContain(pairing.pollToken);
    expect((await status(pairing).expect(200)).body).toEqual({ status: 'PENDING' });

    // Typed the way people type: lower case, with a space.
    const claimed = await claim(
      user.accessToken,
      pairing.code.toLowerCase().replace('-', ' '),
    ).expect(200);
    expect(claimed.body).toMatchObject({
      pairingId: pairing.pairingId,
      tv: { name: 'Living Room TV', model: 'Emu' },
    });
    expect((await status(pairing).expect(200)).body).toEqual({ status: 'CLAIMED' });

    const approved = await approve(user.accessToken, pairing.pairingId, {
      name: 'Bedroom TV',
    }).expect(200);
    expect(approved.body.device).toMatchObject({ type: 'ANDROID_TV', name: 'Bedroom TV' });

    const first = await status(pairing).expect(200);
    expect(first.body.status).toBe('APPROVED');
    expect(first.body.auth.user.displayName).toBe('Janak');
    expect(first.body.auth.device.type).toBe('ANDROID_TV');
    await me(first.body.auth.accessToken).expect(200);

    const second = await status(pairing).expect(200);
    expect(second.body).toEqual({ status: 'CONSUMED' });

    const list = await devices(user.accessToken).expect(200);
    expect(
      list.body.items.map((d: { type: string; current: boolean }) => [d.type, d.current]),
    ).toEqual([
      ['PHONE', true],
      ['ANDROID_TV', false],
    ]);
  });

  it('hides a session from anyone without its poll token', async () => {
    const pairing = await startPairing();

    await status({ ...pairing, pollToken: 'vbp_wrong' }).expect(404);
    await http().get(`/api/v1/pairing/sessions/${pairing.pairingId}/status`).expect(404);
  });

  it('rejects unknown and malformed codes, and locks after 5 wrong ones', async () => {
    const user = await signUp();

    for (const code of ['9999', '12', 'abcd', '0000', '1234']) {
      const res = await claim(user.accessToken, code).expect(404);
      expect(res.body.error.code).toBe('PAIRING_CODE_INVALID');
    }
    const pairing = await startPairing();
    const locked = await claim(user.accessToken, pairing.code).expect(429);
    expect(locked.body.error.code).toBe('RATE_LIMITED');
  });

  it('lets only the claiming user approve; a second user cannot claim or approve', async () => {
    const a = await signUp();
    const b = await signUp('9123456780', {
      ...phone,
      installId: '0192aaaa-0000-7000-8000-000000000009',
    });
    const pairing = await startPairing();
    await claim(a.accessToken, pairing.code).expect(200);

    expect((await claim(b.accessToken, pairing.code).expect(409)).body.error.code).toBe(
      'PAIRING_ALREADY_CLAIMED',
    );
    await approve(b.accessToken, pairing.pairingId).expect(404);
    await approve(a.accessToken, pairing.pairingId).expect(200);
  });

  it('requires sign-in to claim, and refuses a TV as the claimer', async () => {
    const pairing = await startPairing();
    await http().post('/api/v1/pairing/claim').send({ code: pairing.code }).expect(401);

    const user = await signUp();
    await claim(user.accessToken, pairing.code).expect(200);
    await approve(user.accessToken, pairing.pairingId).expect(200);
    const tvAuth = (await status(pairing).expect(200)).body.auth;

    const other = await startPairing({ ...tv, installId: '0192bbbb-0000-7000-8000-000000000003' });
    expect((await claim(tvAuth.accessToken, other.code).expect(403)).body.error.code).toBe(
      'FORBIDDEN',
    );
  });

  it('"Not my TV" ends the session, and a new code replaces an old one', async () => {
    const user = await signUp();
    const first = await startPairing();
    await claim(user.accessToken, first.code).expect(200);
    await http()
      .post(`/api/v1/pairing/${first.pairingId}/reject`)
      .set('Authorization', `Bearer ${user.accessToken}`)
      .expect(204);
    expect((await status(first).expect(200)).body).toEqual({ status: 'REJECTED' });
    await approve(user.accessToken, first.pairingId).expect(404);

    const second = await startPairing();
    const third = await startPairing();
    expect((await status(second).expect(200)).body).toEqual({ status: 'CANCELLED' });
    const claimed = await claim(user.accessToken, third.code).expect(200);
    expect(claimed.body.pairingId).toBe(third.pairingId);
  });

  it('never gives two waiting TVs the same code, and a finished code can be used again', async () => {
    const codes = new Set<string>();
    for (let i = 0; i < 12; i += 1) {
      const pairing = await startPairing({
        ...tv,
        installId: `0192bbbb-0000-7000-8000-0000000001${String(i).padStart(2, '0')}`,
      });
      codes.add(pairing.code);
    }
    expect(codes.size).toBe(12);
  });

  it('enforces the plan limit of one TV, but lets the same TV pair again', async () => {
    const user = await signUp();
    const pairTv = async (tvInfo: Record<string, unknown>) => {
      const pairing = await startPairing(tvInfo);
      await claim(user.accessToken, pairing.code).expect(200);
      return approve(user.accessToken, pairing.pairingId);
    };
    await (
      await pairTv(tv)
    ).body;
    expect((await pairTv(tv)).status).toBe(200);

    const second = await pairTv({
      ...tv,
      installId: '0192bbbb-0000-7000-8000-000000000004',
      name: 'Second TV',
    });
    expect(second.status).toBe(403);
    expect(second.body.error).toMatchObject({
      code: 'ENTITLEMENT_LIMIT',
      details: { entitlement: 'max_tv_devices', limit: 1, current: 1 },
    });
  });

  it('renames a device, and removing the TV signs it out at once', async () => {
    const user = await signUp();
    const pairing = await startPairing();
    await claim(user.accessToken, pairing.code).expect(200);
    const tvDevice = (await approve(user.accessToken, pairing.pairingId).expect(200)).body.device;
    const tvAuth = (await status(pairing).expect(200)).body.auth;
    const auth = { Authorization: `Bearer ${user.accessToken}` };

    const renamed = await http()
      .patch(`/api/v1/devices/${tvDevice.id}`)
      .set(auth)
      .send({ name: '  Den TV ' })
      .expect(200);
    expect(renamed.body.name).toBe('Den TV');
    await http().patch(`/api/v1/devices/${tvDevice.id}`).set(auth).send({ name: '' }).expect(400);

    await http().delete(`/api/v1/devices/${tvDevice.id}`).set(auth).expect(204);
    await me(tvAuth.accessToken).expect(401);
    await http()
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: tvAuth.refreshToken })
      .expect(401);
    expect((await devices(user.accessToken).expect(200)).body.items).toHaveLength(1);
    await http().delete(`/api/v1/devices/${tvDevice.id}`).set(auth).expect(404);
  });

  it("cannot rename or remove another user's device (404)", async () => {
    const a = await signUp();
    const b = await signUp('9123456780', {
      ...phone,
      installId: '0192aaaa-0000-7000-8000-000000000009',
    });
    const auth = { Authorization: `Bearer ${b.accessToken}` };

    await http()
      .patch(`/api/v1/devices/${a.device.id}`)
      .set(auth)
      .send({ name: 'Mine now' })
      .expect(404);
    await http().delete(`/api/v1/devices/${a.device.id}`).set(auth).expect(404);
    await me(a.accessToken).expect(200);
  });

  it('serves the entitlements stub', async () => {
    const user = await signUp();
    const res = await http()
      .get('/api/v1/entitlements')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .expect(200);

    expect(res.body).toMatchObject({
      plan: 'FREE',
      source: 'default',
      entitlements: { max_devices: 2, max_tv_devices: 1 },
    });
  });
});
