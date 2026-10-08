import { Inject, Injectable, Logger } from '@nestjs/common';
import { Device, Prisma, User } from '@prisma/client';
import { AppError } from '../../common/errors/app-error';
import { RateLimitService, rateLimited } from '../../common/rate-limit/rate-limit.service';
import { hashPassword, verifyPassword } from '../../common/utils/crypto';
import { maskPhone, normalizePhone } from '../../common/utils/phone';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { newId } from '../../infra/ids/ids';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { DeviceInfo, DevicesService } from '../devices/devices.service';
import { UserView, toUserView } from '../users/user.mapper';
import { SessionService, TokenPair } from './session.service';

const HOUR = 60 * 60;
export const LIMITS = {
  registerPerIp: 20,
  loginPerIp: 60,
  // Passwords may be a single character (ADR-0008), so guessing is cheap: after this many
  // wrong passwords for one number, that number is locked for the rest of the hour.
  loginFailuresPerPhone: 10,
  refreshPerIp: 120,
};

export interface AuthResult extends TokenPair {
  user: UserView;
  device: Device;
}

export interface RequestContext {
  ipHash: string;
  userAgent?: string;
}

@Injectable()
export class AuthService {
  private readonly security = new Logger('security');
  /** Compared against when the number is unknown, so both failures take the same time. */
  private readonly dummyHash = hashPassword('videobridge-dummy-password');

  constructor(
    private readonly prisma: PrismaService,
    private readonly rateLimit: RateLimitService,
    private readonly devices: DevicesService,
    private readonly sessions: SessionService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  private requirePhone(input: string): string {
    const e164 = normalizePhone(input, this.config.auth.defaultPhoneRegion);
    if (!e164) {
      throw new AppError('VALIDATION_FAILED', 'Enter a valid mobile number.', {
        fields: { phone: ['INVALID_PHONE'] },
      });
    }
    return e164;
  }

  async register(
    input: { name: string; phone: string; password: string; device: DeviceInfo },
    context: RequestContext,
  ): Promise<AuthResult> {
    await this.rateLimit.enforce(`register:ip:${context.ipHash}`, LIMITS.registerPerIp, HOUR);
    const phoneE164 = this.requirePhone(input.phone);
    const now = this.clock.now();

    let user: User;
    try {
      user = await this.prisma.user.create({
        data: {
          id: newId(),
          phoneE164,
          displayName: input.name,
          passwordHash: await hashPassword(input.password),
          lastLoginAt: now,
        },
      });
    } catch (error) {
      // The unique index on phone_e164 decides, so two simultaneous sign-ups can't both win.
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new AppError('PHONE_ALREADY_REGISTERED');
      }
      throw error;
    }
    this.security.log({ event: 'register', userId: user.id, phone: maskPhone(phoneE164) });
    return this.startSession(user, input.device, context);
  }

  async login(
    input: { phone: string; password: string; device: DeviceInfo },
    context: RequestContext,
  ): Promise<AuthResult> {
    await this.rateLimit.enforce(`login:ip:${context.ipHash}`, LIMITS.loginPerIp, HOUR);
    const phoneE164 = this.requirePhone(input.phone);
    const failureKey = `login:fail:phone:${phoneE164}`;

    const lock = await this.rateLimit.isOverLimit(failureKey, LIMITS.loginFailuresPerPhone, HOUR);
    if (!lock.allowed) {
      throw rateLimited(lock.retryAfterSeconds);
    }

    const user = await this.prisma.user.findUnique({ where: { phoneE164 } });
    const passwordOk = await verifyPassword(
      input.password,
      user?.passwordHash ?? (await this.dummyHash),
    );
    if (!user || !passwordOk) {
      const failures = await this.rateLimit.consume(failureKey, LIMITS.loginFailuresPerPhone, HOUR);
      this.security.warn({
        event: failures.allowed ? 'login_failed' : 'login_locked',
        phone: maskPhone(phoneE164),
        ipHash: context.ipHash,
      });
      // Same answer for "no such account" and "wrong password".
      throw new AppError('INVALID_CREDENTIALS');
    }
    if (user.status !== 'ACTIVE') {
      throw new AppError('USER_SUSPENDED');
    }

    const updated = await this.prisma.user.update({
      where: { id: user.id },
      data: { lastLoginAt: this.clock.now() },
    });
    this.security.log({ event: 'login_success', userId: user.id, ipHash: context.ipHash });
    return this.startSession(updated, input.device, context);
  }

  private async startSession(
    user: User,
    deviceInfo: DeviceInfo,
    context: RequestContext,
  ): Promise<AuthResult> {
    const device = await this.devices.registerAtLogin(user.id, deviceInfo);
    const pair = await this.sessions.createSession({
      userId: user.id,
      deviceId: device.id,
      deviceType: device.type,
      tokenVersion: user.tokenVersion,
      userAgent: context.userAgent,
    });
    return { ...pair, user: toUserView(user), device };
  }

  async refresh(refreshToken: string, context: RequestContext): Promise<TokenPair> {
    await this.rateLimit.enforce(`refresh:ip:${context.ipHash}`, LIMITS.refreshPerIp, HOUR);
    return this.sessions.rotate(refreshToken);
  }
}
