import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseEnv } from 'node:util';

// Runs before each e2e file. E2E tests use their own database, never the dev one: either the
// one named in backend/.env.test (e.g. an Atlas `videobridge_test` database) or the disposable
// services from infra/docker-compose.test.yml (ports 57017 / 56379). CI sets the variables directly.
const envFile = join(__dirname, '..', '.env.test');
if (existsSync(envFile)) {
  // Not process.loadEnvFile(): Jest gives tests a copy of process.env, and the native loader
  // writes to the real one, so the values would never be seen here.
  for (const [key, value] of Object.entries(parseEnv(readFileSync(envFile, 'utf8')))) {
    process.env[key] ??= value;
  }
}

process.env.NODE_ENV = 'test';
process.env.APP_ENV ??= 'development';
process.env.DATABASE_URL ??=
  'mongodb://localhost:57017/videobridge_test?replicaSet=rs0&directConnection=true';
process.env.LOG_LEVEL ??= 'fatal';
// Test-only values; real ones come from the environment.
process.env.JWT_ACCESS_SECRET ??= 'e2e-test-secret-e2e-test-secret-e2e-1234';
process.env.IP_HASH_SALT ??= 'e2e-test-salt-1234';
process.env.PAIRING_SECRET ??= 'e2e-test-pairing-secret-e2e-test-1234';
