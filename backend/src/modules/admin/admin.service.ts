import { Inject, Injectable, Logger } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import jwt from 'jsonwebtoken';
import { AppError } from '../../common/errors/app-error';
import { RateLimitService, rateLimited } from '../../common/rate-limit/rate-limit.service';
import { hmacSha256Hex, timingSafeEqualHex } from '../../common/utils/crypto';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { PrismaService } from '../../infra/prisma/prisma.service';

const ISSUER = 'videobridge';
// A different audience from app access tokens, so neither kind is accepted in place of the other.
const AUDIENCE = 'videobridge-admin';
const HOUR = 60 * 60;
export const ADMIN_LIMITS = { loginFailuresPerIp: 10, loginFailuresTotal: 100 };

export interface AdminUserRow {
  id: string;
  name: string;
  phone: string;
  status: string;
  createdAt: string;
  lastLoginAt: string | null;
}

@Injectable()
export class AdminService {
  private readonly security = new Logger('security');

  constructor(
    private readonly prisma: PrismaService,
    private readonly rateLimit: RateLimitService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /** The configured password, or NOT_FOUND: a switched-off admin page does not reveal itself. */
  requireEnabled(): string {
    const password = this.config.admin.password;
    if (!password) {
      throw new AppError('NOT_FOUND');
    }
    return password;
  }

  async login(password: string, ipHash: string): Promise<{ token: string; expiresAt: Date }> {
    const expected = this.requireEnabled();
    const ipKey = `admin:fail:ip:${ipHash}`;
    const totalKey = 'admin:fail:total';
    const [ipLock, totalLock] = await Promise.all([
      this.rateLimit.isOverLimit(ipKey, ADMIN_LIMITS.loginFailuresPerIp, HOUR),
      this.rateLimit.isOverLimit(totalKey, ADMIN_LIMITS.loginFailuresTotal, HOUR),
    ]);
    if (!ipLock.allowed || !totalLock.allowed) {
      throw rateLimited(Math.max(ipLock.retryAfterSeconds, totalLock.retryAfterSeconds));
    }

    // Compared as HMACs so the comparison takes the same time whatever was typed.
    const key = this.config.auth.jwtAccessSecret;
    if (!timingSafeEqualHex(hmacSha256Hex(key, password), hmacSha256Hex(key, expected))) {
      await Promise.all([
        this.rateLimit.consume(ipKey, ADMIN_LIMITS.loginFailuresPerIp, HOUR),
        this.rateLimit.consume(totalKey, ADMIN_LIMITS.loginFailuresTotal, HOUR),
      ]);
      this.security.warn({ event: 'admin_login_failed', ipHash });
      throw new AppError('INVALID_CREDENTIALS', 'Wrong password.');
    }

    const nowSeconds = Math.floor(this.clock.now().getTime() / 1000);
    const exp = nowSeconds + this.config.admin.tokenTtlSeconds;
    const token = jwt.sign(
      { typ: 'admin', iat: nowSeconds, exp, iss: ISSUER, aud: AUDIENCE },
      key,
      { algorithm: 'HS256' },
    );
    this.security.log({ event: 'admin_login_success', ipHash });
    return { token, expiresAt: new Date(exp * 1000) };
  }

  verify(token: string): void {
    this.requireEnabled();
    try {
      const decoded = jwt.verify(token, this.config.auth.jwtAccessSecret, {
        algorithms: ['HS256'],
        issuer: ISSUER,
        audience: AUDIENCE,
        clockTimestamp: Math.floor(this.clock.now().getTime() / 1000),
      });
      if (typeof decoded === 'string' || decoded.typ !== 'admin') {
        throw new AppError('UNAUTHENTICATED');
      }
    } catch {
      throw new AppError('UNAUTHENTICATED');
    }
  }

  async listUsers(input: {
    q?: string;
    page: number;
    limit: number;
  }): Promise<{ total: number; items: AdminUserRow[] }> {
    const q = input.q?.trim();
    const digits = q?.replace(/\D/g, '') ?? '';
    const where: Prisma.UserWhereInput = q
      ? {
          OR: [
            { displayName: { contains: q, mode: 'insensitive' } },
            ...(digits ? [{ phoneE164: { contains: digits } }] : []),
          ],
        }
      : {};
    const [total, users] = await Promise.all([
      this.prisma.user.count({ where }),
      this.prisma.user.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (input.page - 1) * input.limit,
        take: input.limit,
      }),
    ]);
    return {
      total,
      items: users.map((user) => ({
        id: user.id,
        name: user.displayName,
        phone: user.phoneE164,
        status: user.status,
        createdAt: user.createdAt.toISOString(),
        lastLoginAt: user.lastLoginAt?.toISOString() ?? null,
      })),
    };
  }
}
