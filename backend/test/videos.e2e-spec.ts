import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { EntitlementsService } from '../src/modules/entitlements/entitlements.module';
import { createTestApp } from './helpers/app';
import { resetDatabase } from './helpers/db';

const device = {
  installId: '0192aaaa-0000-7000-8000-000000000001',
  type: 'PHONE',
  name: 'Test Phone',
};

describe('Saved videos (e2e)', () => {
  let app: NestExpressApplication;
  const http = () => request(app.getHttpServer());

  const signUp = async (phone = '9876543210') =>
    (
      await http()
        .post('/api/v1/auth/register')
        .send({ name: 'Janak', phone, password: '1', device })
        .expect(201)
    ).body.accessToken as string;
  const save = (token: string, body: Record<string, unknown>) =>
    http().post('/api/v1/videos').set('Authorization', `Bearer ${token}`).send(body);
  const list = async (token: string) =>
    (await http().get('/api/v1/videos').set('Authorization', `Bearer ${token}`).expect(200)).body
      .items;

  beforeAll(async () => {
    app = await createTestApp();
  });
  beforeEach(async () => {
    await resetDatabase(app);
  });
  afterAll(async () => {
    await app.close();
  });

  it('saves a link with a title derived from the file name, and lists newest first', async () => {
    const token = await signUp();

    const first = await save(token, {
      sourceUrl: 'https://cdn.example.com/a/My.Holiday.2024.mp4?utm_source=wa',
    }).expect(201);
    expect(first.body).toMatchObject({
      title: 'My Holiday 2024',
      sourceUrl: 'https://cdn.example.com/a/My.Holiday.2024.mp4?utm_source=wa',
      sourceDomain: 'cdn.example.com',
      positionMs: 0,
      durationMs: null,
      sizeBytes: null,
      format: null,
      thumbnailUrl: null,
    });
    await save(token, {
      sourceUrl: 'https://www.example.com/watch?v=1',
      title: '  Second  ',
    }).expect(201);

    expect((await list(token)).map((video: { title: string }) => video.title)).toEqual([
      'Second',
      'My Holiday 2024',
    ]);
  });

  it('stores the size and format the device reports, and rejects nonsense', async () => {
    const token = await signUp();

    const saved = await save(token, {
      sourceUrl: 'https://cdn.example.com/big.mkv',
      title: 'Big',
      sizeBytes: 5368709120,
      format: 'mkv',
    }).expect(201);
    expect(saved.body).toMatchObject({ title: 'Big', sizeBytes: 5368709120, format: 'MKV' });
    expect((await list(token))[0]).toMatchObject({ sizeBytes: 5368709120, format: 'MKV' });

    await save(token, { sourceUrl: 'https://cdn.example.com/a.mp4', sizeBytes: -1 }).expect(400);
    await save(token, { sourceUrl: 'https://cdn.example.com/b.mp4', format: '<script>' }).expect(
      400,
    );
  });

  it('refuses the same link twice, even with different tracking parameters', async () => {
    const token = await signUp();
    const saved = await save(token, {
      sourceUrl: 'https://cdn.example.com/v.mp4?utm_source=wa',
    }).expect(201);

    const again = await save(token, { sourceUrl: 'https://cdn.example.com/v.mp4#t=10' }).expect(
      409,
    );

    expect(again.body.error).toMatchObject({
      code: 'DUPLICATE_VIDEO',
      details: { existingVideoId: saved.body.id },
    });
  });

  it('is safe to retry with the same client id', async () => {
    const token = await signUp();
    const body = {
      id: '0192cccc-0000-7000-8000-000000000001',
      sourceUrl: 'https://cdn.example.com/v.mp4',
    };

    await save(token, body).expect(201);
    const retry = await save(token, body).expect(200);

    expect(retry.body.id).toBe(body.id);
    expect(await list(token)).toHaveLength(1);
    await save(token, { ...body, sourceUrl: 'https://cdn.example.com/other.mp4' }).expect(409);
  });

  it.each([
    'javascript:alert(1)',
    'file:///a.mp4',
    'not a link',
    'https://user:pw@example.com/a.mp4',
  ])('rejects "%s"', async (sourceUrl) => {
    const token = await signUp();

    expect((await save(token, { sourceUrl }).expect(400)).body.error.code).toBe('URL_NOT_ALLOWED');

    const withPicture = await save(token, {
      sourceUrl: 'https://site.example/watch/1',
      thumbnailUrl: 'https://img.example/p.jpg',
    }).expect(201);
    expect(withPicture.body.thumbnailUrl).toBe('https://img.example/p.jpg');
    await save(token, {
      sourceUrl: 'https://site.example/watch/2',
      thumbnailUrl: 'javascript:alert(1)',
    }).expect(400);
  });

  it('renames, remembers the resume point, deletes, and lets the link be saved again', async () => {
    const token = await signUp();
    const auth = { Authorization: `Bearer ${token}` };
    const video = (await save(token, { sourceUrl: 'https://cdn.example.com/v.mp4' }).expect(201))
      .body;

    const renamed = await http()
      .patch(`/api/v1/videos/${video.id}`)
      .set(auth)
      .send({ title: ' Trip ' })
      .expect(200);
    expect(renamed.body.title).toBe('Trip');
    await http().patch(`/api/v1/videos/${video.id}`).set(auth).send({ title: '' }).expect(400);

    await http()
      .put(`/api/v1/videos/${video.id}/playback`)
      .set(auth)
      .send({ positionMs: 40000, durationMs: 600000 })
      .expect(204);
    expect((await list(token))[0]).toMatchObject({ positionMs: 40000, durationMs: 600000 });
    await http()
      .put(`/api/v1/videos/${video.id}/playback`)
      .set(auth)
      .send({ positionMs: -1 })
      .expect(400);

    await http().delete(`/api/v1/videos/${video.id}`).set(auth).expect(204);
    await http().delete(`/api/v1/videos/${video.id}`).set(auth).expect(204);
    expect(await list(token)).toEqual([]);
    await http().patch(`/api/v1/videos/${video.id}`).set(auth).send({ title: 'x' }).expect(404);
    await save(token, { sourceUrl: 'https://cdn.example.com/v.mp4' }).expect(201);
  });

  it("keeps users apart: no reading, renaming, deleting or resuming someone else's video", async () => {
    const a = await signUp();
    const b = await signUp('9123456780');
    const video = (await save(a, { sourceUrl: 'https://cdn.example.com/v.mp4' }).expect(201)).body;
    const auth = { Authorization: `Bearer ${b}` };

    expect(await list(b)).toEqual([]);
    await http().patch(`/api/v1/videos/${video.id}`).set(auth).send({ title: 'mine' }).expect(404);
    await http()
      .put(`/api/v1/videos/${video.id}/playback`)
      .set(auth)
      .send({ positionMs: 5 })
      .expect(404);
    await http().delete(`/api/v1/videos/${video.id}`).set(auth).expect(204);
    expect(await list(a)).toHaveLength(1);
    // The same link in two libraries is fine.
    await save(b, { sourceUrl: 'https://cdn.example.com/v.mp4' }).expect(201);
  });

  it('requires sign-in', async () => {
    await http().get('/api/v1/videos').expect(401);
    await http()
      .post('/api/v1/videos')
      .send({ sourceUrl: 'https://cdn.example.com/v.mp4' })
      .expect(401);
  });

  it('enforces the plan limit on saved links', async () => {
    const token = await signUp();
    const entitlements = app.get(EntitlementsService);
    const real = await entitlements.getEffective('');
    jest
      .spyOn(entitlements, 'getEffective')
      .mockResolvedValue({ ...real, entitlements: { ...real.entitlements, max_saved_links: 2 } });

    await save(token, { sourceUrl: 'https://cdn.example.com/1.mp4' }).expect(201);
    await save(token, { sourceUrl: 'https://cdn.example.com/2.mp4' }).expect(201);
    const third = await save(token, { sourceUrl: 'https://cdn.example.com/3.mp4' }).expect(403);

    expect(third.body.error).toMatchObject({
      code: 'ENTITLEMENT_LIMIT',
      details: { entitlement: 'max_saved_links', limit: 2, current: 2 },
    });
    jest.restoreAllMocks();
  });
});
