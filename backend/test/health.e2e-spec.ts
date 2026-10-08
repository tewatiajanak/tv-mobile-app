import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { createTestApp } from './helpers/app';

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('Health and error envelope (e2e)', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    app = await createTestApp();
  });

  afterAll(async () => {
    await app.close();
  });

  describe('GET /api/v1/health', () => {
    it('returns 200 with env, version and time', async () => {
      const res = await request(app.getHttpServer()).get('/api/v1/health').expect(200);

      expect(res.body).toEqual({
        status: 'ok',
        env: 'development',
        version: expect.any(String),
        time: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
      });
    });

    it('generates and echoes X-Request-Id', async () => {
      const res = await request(app.getHttpServer()).get('/api/v1/health').expect(200);

      expect(res.headers['x-request-id']).toMatch(UUID_V7);
    });

    it('echoes a client-supplied X-Request-Id', async () => {
      const res = await request(app.getHttpServer())
        .get('/api/v1/health')
        .set('X-Request-Id', 'client-req-42')
        .expect(200);

      expect(res.headers['x-request-id']).toBe('client-req-42');
    });

    it('sets security headers and sends no CORS header to an unknown origin', async () => {
      const res = await request(app.getHttpServer())
        .get('/api/v1/health')
        .set('Origin', 'https://evil.example')
        .expect(200);

      expect(res.headers['x-content-type-options']).toBe('nosniff');
      expect(res.headers['x-powered-by']).toBeUndefined();
      expect(res.headers['access-control-allow-origin']).toBeUndefined();
    });
  });

  describe('GET /api/v1/health/ready', () => {
    // Needs a reachable MongoDB (see test/setup-e2e.ts). Redis is 'disabled' unless REDIS_URL is set.
    it('returns 200 when MongoDB is reachable', async () => {
      const res = await request(app.getHttpServer()).get('/api/v1/health/ready').expect(200);

      expect(res.body).toEqual({
        status: 'ok',
        checks: { db: 'up', redis: expect.stringMatching(/^(up|disabled)$/) },
      });
    });
  });

  describe('error envelope', () => {
    it('returns a 404 envelope with requestId for an unknown route', async () => {
      const res = await request(app.getHttpServer())
        .get('/api/v1/does-not-exist?token=secret')
        .set('X-Request-Id', 'req-404')
        .expect(404);

      expect(res.body).toEqual({
        error: { code: 'NOT_FOUND', message: 'Not found.', requestId: 'req-404' },
      });
      expect(res.headers['x-request-id']).toBe('req-404');
    });

    it('returns a 404 envelope outside the API prefix too', async () => {
      const res = await request(app.getHttpServer()).get('/nope').expect(404);

      expect(res.body.error.code).toBe('NOT_FOUND');
      expect(res.body.error.requestId).toBe(res.headers['x-request-id']);
    });

    it('returns VALIDATION_FAILED with details.fields for an invalid body', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/_test/echo')
        .send({ name: '', count: 9, userId: 'someone-else' })
        .expect(400);

      expect(res.body.error).toEqual({
        code: 'VALIDATION_FAILED',
        message: 'The request is invalid.',
        details: {
          fields: {
            name: [expect.any(String)],
            count: [expect.any(String)],
            userId: ['property userId should not exist'],
          },
        },
        requestId: res.headers['x-request-id'],
      });
    });

    it('accepts a valid body', async () => {
      await request(app.getHttpServer())
        .post('/api/v1/_test/echo')
        .send({ name: 'tv', count: 2 })
        .expect(201, { name: 'tv', count: 2 });
    });

    it('returns an envelope for malformed JSON', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/_test/echo')
        .set('Content-Type', 'application/json')
        .send('{"name": ')
        .expect(400);

      expect(res.body.error.code).toBe('BAD_REQUEST');
      expect(res.body.error.requestId).toEqual(expect.any(String));
    });

    it('rejects bodies over 100 KB with PAYLOAD_TOO_LARGE', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/_test/echo')
        .send({ name: 'x'.repeat(150 * 1024), count: 1 })
        .expect(413);

      expect(res.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    });

    it('hides internal details of an unexpected error', async () => {
      const res = await request(app.getHttpServer()).get('/api/v1/_test/boom').expect(500);

      expect(res.body.error).toEqual({
        code: 'INTERNAL',
        message: 'Something went wrong. Please try again.',
        requestId: res.headers['x-request-id'],
      });
      expect(JSON.stringify(res.body)).not.toContain('SELECT');
    });
  });

  describe('Swagger', () => {
    it('serves the UI and the JSON document', async () => {
      await request(app.getHttpServer())
        .get('/api/docs')
        .expect(200)
        .expect('Content-Type', /html/);
      const res = await request(app.getHttpServer()).get('/api/docs-json').expect(200);

      expect(Object.keys(res.body.paths)).toEqual(
        expect.arrayContaining(['/api/v1/health', '/api/v1/health/ready', '/api/v1/auth/login']),
      );
      expect(Object.keys(res.body.paths).some((path) => path.includes('_test'))).toBe(false);
    });
  });
});

describe('Readiness when dependencies are down (e2e)', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    // Port 9 (discard) is closed on a developer machine and in CI, so both connections are refused.
    app = await createTestApp({
      databaseUrl:
        'mongodb://127.0.0.1:9/videobridge_test?serverSelectionTimeoutMS=1000&connectTimeoutMS=1000',
      redisUrl: 'redis://127.0.0.1:9/0',
    });
  });

  afterAll(async () => {
    await app.close();
  });

  it('returns 503 NOT_READY and says which checks failed', async () => {
    const res = await request(app.getHttpServer()).get('/api/v1/health/ready').expect(503);

    expect(res.body).toEqual({
      error: {
        code: 'NOT_READY',
        message: 'The service is not ready.',
        details: { checks: { db: 'down', redis: 'down' } },
        requestId: res.headers['x-request-id'],
      },
    });
  });

  it('keeps liveness at 200', async () => {
    await request(app.getHttpServer()).get('/api/v1/health').expect(200);
  });
});

describe('Swagger disabled (e2e)', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    app = await createTestApp({ swaggerEnabled: false });
  });

  afterAll(async () => {
    await app.close();
  });

  it('does not serve the docs', async () => {
    const res = await request(app.getHttpServer()).get('/api/docs').expect(404);

    expect(res.body.error.code).toBe('NOT_FOUND');
  });
});
