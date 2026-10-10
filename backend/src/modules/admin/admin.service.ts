import { randomInt } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import jwt from 'jsonwebtoken';
import { AppError } from '../../common/errors/app-error';
import { RateLimitService, rateLimited } from '../../common/rate-limit/rate-limit.service';
import {
  hashPassword,
  hmacSha256Hex,
  timingSafeEqualHex,
  verifyPassword,
} from '../../common/utils/crypto';
import { normalizePhone } from '../../common/utils/phone';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { AdminMailer } from './admin.mailer';

const ISSUER = 'videobridge';
// A different audience from app access tokens, so neither kind is accepted in place of the other.
const AUDIENCE = 'videobridge-admin';
const HOUR = 60 * 60;
const CREDENTIAL_ID = 'master';
export const ADMIN_LIMITS = {
  loginFailuresPerIp: 10,
  loginFailuresTotal: 100,
  resetRequestsPerIp: 5,
  resetEmailsTotal: 5,
  otpAttempts: 5,
  otpTtlSeconds: 10 * 60,
};

export interface AdminUserRow {
  id: string;
  name: string;
  phone: string;
  status: string;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AdminToken {
  token: string;
  expiresAt: Date;
}

@Injectable()
export class AdminService {
  private readonly security = new Logger('security');

  constructor(
    private readonly prisma: PrismaService,
    private readonly rateLimit: RateLimitService,
    private readonly mailer: AdminMailer,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /** The admin's number and first password, or NOT_FOUND: a switched-off page does not reveal itself. */
  requireEnabled(): { phone: string; password: string } {
    const { phone, password } = this.config.admin;
    if (!phone || !password) {
      throw new AppError('NOT_FOUND');
    }
    return { phone, password };
  }

  private get key(): string {
    return this.config.auth.jwtAccessSecret;
  }

  // Compared as HMACs so the comparison takes the same time whatever was typed.
  private same(left: string, right: string): boolean {
    return timingSafeEqualHex(hmacSha256Hex(this.key, left), hmacSha256Hex(this.key, right));
  }

  private isAdminPhone(input: string, adminPhone: string): boolean {
    const typed = normalizePhone(input, this.config.auth.defaultPhoneRegion) ?? '';
    return this.same(typed, adminPhone);
  }

  private credential() {
    return this.prisma.adminCredential.findUnique({ where: { id: CREDENTIAL_ID } });
  }

  /** The stored hash once the password has been changed, the configured first password before. */
  private async passwordMatches(password: string, initial: string): Promise<boolean> {
    const stored = await this.credential();
    return stored ? verifyPassword(password, stored.passwordHash) : this.same(password, initial);
  }

  private async assertNotLocked(ipHash: string): Promise<void> {
    const [ipLock, totalLock] = await Promise.all([
      this.rateLimit.isOverLimit(`admin:fail:ip:${ipHash}`, ADMIN_LIMITS.loginFailuresPerIp, HOUR),
      this.rateLimit.isOverLimit('admin:fail:total', ADMIN_LIMITS.loginFailuresTotal, HOUR),
    ]);
    if (!ipLock.allowed || !totalLock.allowed) {
      throw rateLimited(Math.max(ipLock.retryAfterSeconds, totalLock.retryAfterSeconds));
    }
  }

  private async countFailure(event: string, ipHash: string): Promise<void> {
    await Promise.all([
      this.rateLimit.consume(`admin:fail:ip:${ipHash}`, ADMIN_LIMITS.loginFailuresPerIp, HOUR),
      this.rateLimit.consume('admin:fail:total', ADMIN_LIMITS.loginFailuresTotal, HOUR),
    ]);
    this.security.warn({ event, ipHash });
  }

  private issue(version: number): AdminToken {
    const nowSeconds = Math.floor(this.clock.now().getTime() / 1000);
    const exp = nowSeconds + this.config.admin.tokenTtlSeconds;
    const token = jwt.sign(
      { typ: 'admin', ver: version, iat: nowSeconds, exp, iss: ISSUER, aud: AUDIENCE },
      this.key,
      { algorithm: 'HS256' },
    );
    return { token, expiresAt: new Date(exp * 1000) };
  }

  /** Stores a new password and signs every earlier admin token out. */
  private async storePassword(password: string): Promise<AdminToken> {
    const passwordHash = await hashPassword(password);
    const saved = await this.prisma.adminCredential.upsert({
      where: { id: CREDENTIAL_ID },
      create: { id: CREDENTIAL_ID, passwordHash, tokenVersion: 1 },
      update: {
        passwordHash,
        tokenVersion: { increment: 1 },
        otpHash: null,
        otpExpiresAt: null,
        otpAttempts: 0,
      },
    });
    return this.issue(saved.tokenVersion);
  }

  async login(phone: string, password: string, ipHash: string): Promise<AdminToken> {
    const admin = this.requireEnabled();
    await this.assertNotLocked(ipHash);

    const phoneOk = this.isAdminPhone(phone, admin.phone);
    const passwordOk = await this.passwordMatches(password, admin.password);
    if (!phoneOk || !passwordOk) {
      await this.countFailure('admin_login_failed', ipHash);
      throw new AppError('INVALID_CREDENTIALS');
    }

    this.security.log({ event: 'admin_login_success', ipHash });
    return this.issue((await this.credential())?.tokenVersion ?? 0);
  }

  async verify(token: string): Promise<void> {
    this.requireEnabled();
    let version: unknown;
    try {
      const decoded = jwt.verify(token, this.key, {
        algorithms: ['HS256'],
        issuer: ISSUER,
        audience: AUDIENCE,
        clockTimestamp: Math.floor(this.clock.now().getTime() / 1000),
      });
      if (typeof decoded === 'string' || decoded.typ !== 'admin') {
        throw new AppError('UNAUTHENTICATED');
      }
      version = decoded.ver;
    } catch {
      throw new AppError('UNAUTHENTICATED');
    }
    if (version !== ((await this.credential())?.tokenVersion ?? 0)) {
      throw new AppError('UNAUTHENTICATED');
    }
  }

  async changePassword(current: string, next: string, ipHash: string): Promise<AdminToken> {
    const admin = this.requireEnabled();
    await this.assertNotLocked(ipHash);
    if (!(await this.passwordMatches(current, admin.password))) {
      await this.countFailure('admin_password_change_failed', ipHash);
      throw new AppError('INVALID_CREDENTIALS', 'The current password is wrong.');
    }
    this.security.log({ event: 'admin_password_changed', ipHash });
    return this.storePassword(next);
  }

  /**
   * Emails a reset code to the configured admin address, and only when the admin's own number
   * was typed. The answer is the same either way, so the page does not confirm the number.
   */
  async requestReset(phone: string, ipHash: string): Promise<void> {
    const admin = this.requireEnabled();
    if (!this.mailer.configured) {
      throw new AppError('SERVICE_UNAVAILABLE', 'Password reset by email is not set up.');
    }
    await this.rateLimit.enforce(`admin:reset:ip:${ipHash}`, ADMIN_LIMITS.resetRequestsPerIp, HOUR);
    if (!this.isAdminPhone(phone, admin.phone)) {
      this.security.warn({ event: 'admin_reset_wrong_number', ipHash });
      return;
    }
    const emails = await this.rateLimit.consume(
      'admin:reset:emails',
      ADMIN_LIMITS.resetEmailsTotal,
      HOUR,
    );
    if (!emails.allowed) {
      return;
    }

    const otp = randomInt(0, 1_000_000).toString().padStart(6, '0');
    const pending = {
      otpHash: hmacSha256Hex(this.key, otp),
      otpExpiresAt: new Date(this.clock.now().getTime() + ADMIN_LIMITS.otpTtlSeconds * 1000),
      otpAttempts: 0,
    };
    await this.prisma.adminCredential.upsert({
      where: { id: CREDENTIAL_ID },
      // No stored password yet: keep the first password valid until the reset completes.
      create: { id: CREDENTIAL_ID, passwordHash: await hashPassword(admin.password), ...pending },
      update: pending,
    });
    const sent = await this.mailer.send(
      'Dekho admin password reset code',
      `Your Dekho admin code is ${otp}. It is valid for 10 minutes. If you did not ask for it, ignore this email.`,
    );
    this.security.log({
      event: sent ? 'admin_reset_code_sent' : 'admin_reset_email_failed',
      ipHash,
    });
  }

  async resetPassword(
    phone: string,
    otp: string,
    next: string,
    ipHash: string,
  ): Promise<AdminToken> {
    const admin = this.requireEnabled();
    await this.assertNotLocked(ipHash);

    const stored = await this.credential();
    const live =
      this.isAdminPhone(phone, admin.phone) &&
      !!stored?.otpHash &&
      !!stored.otpExpiresAt &&
      stored.otpExpiresAt.getTime() > this.clock.now().getTime() &&
      stored.otpAttempts < ADMIN_LIMITS.otpAttempts;
    if (!live || !timingSafeEqualHex(hmacSha256Hex(this.key, otp), stored.otpHash ?? '')) {
      if (live) {
        await this.prisma.adminCredential.update({
          where: { id: CREDENTIAL_ID },
          data: { otpAttempts: { increment: 1 } },
        });
      }
      await this.countFailure('admin_reset_failed', ipHash);
      throw new AppError('INVALID_CREDENTIALS', 'Wrong or expired code.');
    }

    this.security.log({ event: 'admin_password_reset', ipHash });
    return this.storePassword(next);
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
