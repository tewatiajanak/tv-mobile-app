import { z } from 'zod';

export const NODE_ENVS = ['development', 'test', 'production'] as const;
export const APP_ENVS = ['development', 'staging', 'production'] as const;
export const LOG_LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace'] as const;

// Messages are static on purpose: a boot failure must never echo a (possibly secret) value.
const bool = (name: string) =>
  z
    .enum(['true', 'false'], { error: `${name} must be "true" or "false"` })
    .transform((v) => v === 'true');

const positiveInt = (name: string) =>
  z.coerce
    .number({ error: `${name} must be a positive integer` })
    .int({ error: `${name} must be a positive integer` })
    .min(1, { error: `${name} must be a positive integer` });

const urlWithScheme = (name: string, schemes: readonly string[]) =>
  z.string({ error: `${name} is required` }).refine(
    (value) => {
      try {
        return schemes.includes(new URL(value).protocol);
      } catch {
        return false;
      }
    },
    { error: `${name} must be a valid ${schemes.map((s) => `${s}//`).join(' or ')} URL` },
  );

export const envSchema = z.object({
  NODE_ENV: z.enum(NODE_ENVS, { error: `NODE_ENV must be one of ${NODE_ENVS.join(' | ')}` }),
  APP_ENV: z.enum(APP_ENVS, { error: `APP_ENV must be one of ${APP_ENVS.join(' | ')}` }),
  PORT: z.coerce
    .number({ error: 'PORT must be an integer between 1 and 65535' })
    .int({ error: 'PORT must be an integer between 1 and 65535' })
    .min(1, { error: 'PORT must be an integer between 1 and 65535' })
    .max(65535, { error: 'PORT must be an integer between 1 and 65535' })
    .default(3000),
  // Not parsed with URL(): a replica-set string lists several hosts ("h1:27017,h2:27017"),
  // which the WHATWG parser rejects.
  DATABASE_URL: z
    .string({ error: 'DATABASE_URL is required' })
    .regex(/^mongodb(\+srv)?:\/\/[^\s/]+(\/\S*)?$/, {
      error: 'DATABASE_URL must be a valid mongodb+srv:// or mongodb:// URL',
    }),
  // Optional: without it the backend runs with no Redis at all (ADR-0007).
  REDIS_URL: urlWithScheme('REDIS_URL', ['redis:', 'rediss:']).optional(),
  LOG_LEVEL: z
    .enum(LOG_LEVELS, { error: `LOG_LEVEL must be one of ${LOG_LEVELS.join(' | ')}` })
    .default('info'),
  CORS_ORIGINS: z
    .string()
    .default('')
    .transform((value) =>
      value
        .split(',')
        .map((origin) => origin.trim())
        .filter((origin) => origin.length > 0),
    ),
  // Default depends on APP_ENV, so it is resolved in app-config.ts.
  SWAGGER_ENABLED: bool('SWAGGER_ENABLED').optional(),
  TRUST_PROXY: bool('TRUST_PROXY').default(false),

  // --- Authentication (Phase 2) ---
  JWT_ACCESS_SECRET: z
    .string({ error: 'JWT_ACCESS_SECRET is required' })
    .min(32, { error: 'JWT_ACCESS_SECRET must be at least 32 characters' }),
  JWT_ACCESS_TTL_SECONDS: positiveInt('JWT_ACCESS_TTL_SECONDS').default(900),
  REFRESH_TTL_DAYS_PHONE: positiveInt('REFRESH_TTL_DAYS_PHONE').default(180),
  REFRESH_TTL_DAYS_TV: positiveInt('REFRESH_TTL_DAYS_TV').default(180),
  REFRESH_REUSE_GRACE_SECONDS: positiveInt('REFRESH_REUSE_GRACE_SECONDS').default(20),
  IP_HASH_SALT: z
    .string({ error: 'IP_HASH_SALT is required' })
    .min(16, { error: 'IP_HASH_SALT must be at least 16 characters' }),
  // --- TV pairing (Phase 3) ---
  PAIRING_SECRET: z
    .string({ error: 'PAIRING_SECRET is required' })
    .min(32, { error: 'PAIRING_SECRET must be at least 32 characters' }),
  PAIRING_TTL_SECONDS: positiveInt('PAIRING_TTL_SECONDS').default(300),
  // --- Admin page ---
  // Optional: without it the admin page and its API do not exist (404).
  ADMIN_PASSWORD: z
    .string()
    .min(12, { error: 'ADMIN_PASSWORD must be at least 12 characters' })
    .optional(),
  DEFAULT_PHONE_REGION: z
    .string()
    .regex(/^[A-Z]{2}$/, { error: 'DEFAULT_PHONE_REGION must be a 2-letter country code' })
    .default('IN'),
});

export type Env = z.infer<typeof envSchema>;

export type EnvParseResult = { ok: true; env: Env } | { ok: false; problems: string[] };

/** Validates raw environment variables. Problems name the variable, never its value. */
export function parseEnv(raw: Record<string, string | undefined>): EnvParseResult {
  // An empty string in a .env file means "not set".
  const cleaned = Object.fromEntries(
    Object.entries(raw).filter(([, value]) => value !== undefined && value !== ''),
  );
  const result = envSchema.safeParse(cleaned);
  if (result.success) {
    return { ok: true, env: result.data };
  }
  const problems = result.error.issues.map((issue) => {
    const name = issue.path.join('.') || '(root)';
    return issue.message.startsWith(name) ? issue.message : `${name}: ${issue.message}`;
  });
  return { ok: false, problems: [...new Set(problems)] };
}
