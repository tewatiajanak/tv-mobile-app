import { ConfigValidationError, loadConfig } from './app-config';
import { parseEnv } from './env.schema';

const valid = {
  NODE_ENV: 'development',
  APP_ENV: 'development',
  DATABASE_URL: 'mongodb://localhost:27017/videobridge',
  REDIS_URL: 'redis://localhost:6379/0',
  JWT_ACCESS_SECRET: 'unit-test-secret-unit-test-secret-1234',
  IP_HASH_SALT: 'unit-test-salt-1234',
  PAIRING_SECRET: 'unit-test-pairing-secret-unit-test-1234',
};

describe('parseEnv', () => {
  it('accepts a minimal valid environment and applies defaults', () => {
    const result = parseEnv(valid);
    expect(result).toEqual({
      ok: true,
      env: {
        ...valid,
        PORT: 3000,
        LOG_LEVEL: 'info',
        CORS_ORIGINS: [],
        TRUST_PROXY: false,
        JWT_ACCESS_TTL_SECONDS: 900,
        REFRESH_TTL_DAYS_PHONE: 180,
        REFRESH_TTL_DAYS_TV: 180,
        REFRESH_REUSE_GRACE_SECONDS: 20,
        DEFAULT_PHONE_REGION: 'IN',
        PAIRING_TTL_SECONDS: 300,
      },
    });
  });

  it('parses every optional variable', () => {
    const result = parseEnv({
      ...valid,
      PORT: '4100',
      LOG_LEVEL: 'debug',
      CORS_ORIGINS: 'http://localhost:5173, https://admin.example.com ,',
      SWAGGER_ENABLED: 'false',
      TRUST_PROXY: 'true',
    });
    expect(result.ok && result.env).toMatchObject({
      PORT: 4100,
      LOG_LEVEL: 'debug',
      CORS_ORIGINS: ['http://localhost:5173', 'https://admin.example.com'],
      SWAGGER_ENABLED: false,
      TRUST_PROXY: true,
    });
  });

  it('treats empty strings as unset', () => {
    const result = parseEnv({ ...valid, PORT: '', LOG_LEVEL: '' });
    expect(result.ok && result.env).toMatchObject({ PORT: 3000, LOG_LEVEL: 'info' });
  });

  it('reports a missing DATABASE_URL by name', () => {
    const result = parseEnv({ ...valid, DATABASE_URL: undefined });
    expect(result).toEqual({ ok: false, problems: ['DATABASE_URL is required'] });
  });

  it.each([
    ['DATABASE_URL', 'mysql://root:hunter2@localhost/db'],
    ['DATABASE_URL', 'mongodb://'],
    ['DATABASE_URL', 'postgresql://vb:hunter2@localhost:5432/videobridge'],
    ['DATABASE_URL', 'not a url hunter2'],
    ['REDIS_URL', 'http://hunter2@localhost:6379'],
    ['PORT', '70000'],
    ['PORT', 'hunter2'],
    ['NODE_ENV', 'hunter2'],
    ['APP_ENV', 'hunter2'],
    ['LOG_LEVEL', 'hunter2'],
    ['TRUST_PROXY', 'hunter2'],
    ['SWAGGER_ENABLED', 'hunter2'],
    ['JWT_ACCESS_SECRET', 'hunter2'],
    ['IP_HASH_SALT', 'hunter2'],
    ['JWT_ACCESS_TTL_SECONDS', 'hunter2'],
    ['DEFAULT_PHONE_REGION', 'hunter2'],
  ])('rejects an invalid %s without echoing the value', (name, value) => {
    const result = parseEnv({ ...valid, [name]: value });
    expect(result.ok).toBe(false);
    const problems = result.ok ? [] : result.problems;
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain(name);
    expect(problems.join('\n')).not.toContain('hunter2');
  });

  it('accepts an Atlas SRV connection string', () => {
    const result = parseEnv({
      ...valid,
      DATABASE_URL:
        'mongodb+srv://user:pass@cluster.example.mongodb.net/videobridge?retryWrites=true',
    });
    expect(result.ok).toBe(true);
  });

  it('accepts a multi-host replica-set connection string', () => {
    const result = parseEnv({
      ...valid,
      DATABASE_URL:
        'mongodb://user:pass@h1.example.net:27017,h2.example.net:27017/videobridge?tls=true&replicaSet=rs0',
    });
    expect(result.ok).toBe(true);
  });

  it('lists every invalid variable at once', () => {
    const result = parseEnv({ NODE_ENV: 'development' });
    expect(result.ok).toBe(false);
    const problems = (result.ok ? [] : result.problems).join('\n');
    expect(problems).toContain('APP_ENV');
    expect(problems).toContain('DATABASE_URL');
  });
});

describe('loadConfig', () => {
  it('runs without Redis when REDIS_URL is not set', () => {
    expect(loadConfig({ ...valid, REDIS_URL: undefined }).redisUrl).toBeUndefined();
  });

  it('enables Swagger by default outside production', () => {
    expect(loadConfig({ ...valid, APP_ENV: 'staging' }).swaggerEnabled).toBe(true);
  });

  it('disables Swagger by default in production', () => {
    expect(loadConfig({ ...valid, APP_ENV: 'production' }).swaggerEnabled).toBe(false);
  });

  it('lets SWAGGER_ENABLED override the default', () => {
    const config = loadConfig({ ...valid, APP_ENV: 'production', SWAGGER_ENABLED: 'true' });
    expect(config.swaggerEnabled).toBe(true);
  });

  it('prefers GIT_SHA for the version', () => {
    expect(loadConfig({ ...valid, GIT_SHA: 'abc1234' }).version).toBe('abc1234');
  });

  it('throws a readable error listing the invalid variables', () => {
    expect(() => loadConfig({ ...valid, DATABASE_URL: 'nope', REDIS_URL: 'http://x' })).toThrow(
      ConfigValidationError,
    );
    try {
      loadConfig({ ...valid, DATABASE_URL: 'nope', REDIS_URL: 'http://x' });
    } catch (error) {
      expect((error as Error).message).toContain('Invalid environment configuration:');
      expect((error as Error).message).toContain(
        '  - DATABASE_URL must be a valid mongodb+srv:// or mongodb:// URL',
      );
      expect((error as Error).message).toContain(
        '  - REDIS_URL must be a valid redis:// or rediss:// URL',
      );
    }
  });
});
