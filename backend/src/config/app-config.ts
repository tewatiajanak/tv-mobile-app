import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { normalizePhone } from '../common/utils/phone';
import { APP_ENVS, LOG_LEVELS, NODE_ENVS, parseEnv } from './env.schema';

export const APP_CONFIG = Symbol('APP_CONFIG');

export interface AppConfig {
  nodeEnv: (typeof NODE_ENVS)[number];
  appEnv: (typeof APP_ENVS)[number];
  port: number;
  databaseUrl: string;
  /** Undefined when Redis is not used. */
  redisUrl?: string;
  logLevel: (typeof LOG_LEVELS)[number];
  corsOrigins: string[];
  swaggerEnabled: boolean;
  trustProxy: boolean;
  auth: {
    jwtAccessSecret: string;
    accessTtlSeconds: number;
    refreshTtlDaysPhone: number;
    refreshTtlDaysTv: number;
    refreshReuseGraceSeconds: number;
    ipHashSalt: string;
    defaultPhoneRegion: string;
  };
  pairing: { secret: string; ttlSeconds: number };
  /** Without both `phone` (E.164) and `password` the admin page is switched off. */
  admin: {
    phone?: string;
    /** The first password only; a changed password lives hashed in the database. */
    password?: string;
    /** Where password reset codes are sent. Reset needs this and `resendApiKey`. */
    email?: string;
    resendApiKey?: string;
    emailFrom: string;
    tokenTtlSeconds: number;
  };
  /** Git SHA when the build provides one, otherwise the package version. */
  version: string;
  /** True only for the OpenAPI export: no database or Redis connection is opened. */
  docsOnly: boolean;
}

export class ConfigValidationError extends Error {
  constructor(readonly problems: string[]) {
    super(
      ['Invalid environment configuration:', ...problems.map((problem) => `  - ${problem}`)].join(
        '\n',
      ),
    );
    this.name = 'ConfigValidationError';
  }
}

function resolveVersion(raw: Record<string, string | undefined>): string {
  if (raw.GIT_SHA) {
    return raw.GIT_SHA;
  }
  try {
    const pkg: unknown = JSON.parse(readFileSync(join(process.cwd(), 'package.json'), 'utf8'));
    if (typeof pkg === 'object' && pkg !== null && 'version' in pkg) {
      return String(pkg.version);
    }
  } catch {
    // Running outside the package directory; fall through.
  }
  return 'unknown';
}

export function loadConfig(raw: Record<string, string | undefined> = process.env): AppConfig {
  const result = parseEnv(raw);
  if (!result.ok) {
    throw new ConfigValidationError(result.problems);
  }
  const { env } = result;
  const adminPhone = env.ADMIN_PHONE
    ? normalizePhone(env.ADMIN_PHONE, env.DEFAULT_PHONE_REGION)
    : undefined;
  if (adminPhone === null) {
    throw new ConfigValidationError(['ADMIN_PHONE is not a valid mobile number']);
  }
  return {
    nodeEnv: env.NODE_ENV,
    appEnv: env.APP_ENV,
    port: env.PORT,
    databaseUrl: env.DATABASE_URL,
    redisUrl: env.REDIS_URL,
    logLevel: env.LOG_LEVEL,
    corsOrigins: env.CORS_ORIGINS,
    swaggerEnabled: env.SWAGGER_ENABLED ?? env.APP_ENV !== 'production',
    trustProxy: env.TRUST_PROXY,
    auth: {
      jwtAccessSecret: env.JWT_ACCESS_SECRET,
      accessTtlSeconds: env.JWT_ACCESS_TTL_SECONDS,
      refreshTtlDaysPhone: env.REFRESH_TTL_DAYS_PHONE,
      refreshTtlDaysTv: env.REFRESH_TTL_DAYS_TV,
      refreshReuseGraceSeconds: env.REFRESH_REUSE_GRACE_SECONDS,
      ipHashSalt: env.IP_HASH_SALT,
      defaultPhoneRegion: env.DEFAULT_PHONE_REGION,
    },
    pairing: { secret: env.PAIRING_SECRET, ttlSeconds: env.PAIRING_TTL_SECONDS },
    admin: {
      phone: adminPhone,
      password: env.ADMIN_PASSWORD,
      email: env.ADMIN_EMAIL,
      resendApiKey: env.RESEND_API_KEY,
      emailFrom: env.ADMIN_EMAIL_FROM,
      tokenTtlSeconds: 30 * 60,
    },
    version: resolveVersion(raw),
    docsOnly: raw.VB_DOCS_ONLY === '1',
  };
}
