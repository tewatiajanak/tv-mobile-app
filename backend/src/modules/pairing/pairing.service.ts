import { Inject, Injectable, Logger } from '@nestjs/common';
import { PairingSession } from '@prisma/client';
import { randomInt } from 'node:crypto';
import { AppError } from '../../common/errors/app-error';
import { RateLimitService, rateLimited } from '../../common/rate-limit/rate-limit.service';
import {
  decryptSecret,
  deriveKey,
  encryptSecret,
  hmacSha256Hex,
  randomToken,
  sha256Hex,
  timingSafeEqualHex,
} from '../../common/utils/crypto';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { newId } from '../../infra/ids/ids';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { SessionService } from '../auth/session.service';
import { DeviceInfo, DevicesService } from '../devices/devices.service';
import { EntitlementsService } from '../entitlements/entitlements.module';
import { toUserView } from '../users/user.mapper';

// Four digits (owner's choice): quick to read off a TV and type on a phone. That is only
// 10,000 possibilities, so safety rests on the short lifetime and the wrong-code lockout.
export const CODE_ALPHABET = '0123456789';
export const CODE_LENGTH = 4;
const POLL_INTERVAL_MS = 2000;
const HOUR = 60 * 60;
const TEN_MINUTES = 10 * 60;

export function generateCode(): string {
  return Array.from(
    { length: CODE_LENGTH },
    () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)],
  ).join('');
}

/** "12 34", "12-34" -> "1234"; null when it cannot be a code. */
export function normalizeCode(input: string): string | null {
  const code = input.replace(/[\s-]/g, '');
  return /^\d{4}$/.test(code) ? code : null;
}

export type TvInfo = Omit<DeviceInfo, 'type'>;

/** The sign-in handed to the TV exactly once. */
export interface PairingAuth {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: ReturnType<typeof toUserView>;
  device: { id: string; type: string; name: string };
  session: { id: string };
}

/**
 * TV pairing: the TV shows a code, a signed-in phone claims it and approves, and the TV picks up
 * its sign-in. State machine: PENDING -> CLAIMED -> APPROVED -> CONSUMED, or REJECTED / EXPIRED /
 * CANCELLED. Every transition is a conditional update on the current status, so two requests
 * racing for the same transition cannot both win.
 */
@Injectable()
export class PairingService {
  private readonly security = new Logger('security');
  private readonly codeKey: string;
  private readonly tokenKey: Buffer;

  constructor(
    private readonly prisma: PrismaService,
    private readonly rateLimit: RateLimitService,
    private readonly devices: DevicesService,
    private readonly sessions: SessionService,
    private readonly entitlements: EntitlementsService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {
    this.codeKey = deriveKey(config.pairing.secret, 'pairing-code').toString('hex');
    this.tokenKey = deriveKey(config.pairing.secret, 'pairing-tokens');
  }

  private hashCode(code: string): string {
    return hmacSha256Hex(this.codeKey, code);
  }

  /**
   * With only 10,000 codes, two TVs waiting at once could draw the same one. A code is reused
   * only once no open session holds it.
   */
  private async unusedCode(now: Date): Promise<string> {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const code = generateCode();
      const taken = await this.prisma.pairingSession.findFirst({
        where: {
          codeHash: this.hashCode(code),
          status: { in: ['PENDING', 'CLAIMED'] },
          expiresAt: { gt: now },
        },
      });
      if (!taken) {
        return code;
      }
    }
    throw new AppError(
      'SERVICE_UNAVAILABLE',
      'Too many TVs are connecting right now. Try again in a minute.',
    );
  }

  /** Called by a signed-out TV. A new request replaces that TV's earlier open ones. */
  async create(tv: TvInfo, ipHash: string) {
    await this.rateLimit.enforce(`pairing:create:install:${tv.installId}`, 30, HOUR);
    await this.rateLimit.enforce(`pairing:create:ip:${ipHash}`, 60, HOUR);
    const now = this.clock.now();
    await this.prisma.pairingSession.updateMany({
      where: { tvInstallId: tv.installId, status: { in: ['PENDING', 'CLAIMED'] } },
      data: { status: 'CANCELLED' },
    });

    const code = await this.unusedCode(now);
    const pollToken = `vbp_${randomToken(32)}`;
    const session = await this.prisma.pairingSession.create({
      data: {
        id: newId(),
        codeHash: this.hashCode(code),
        pollTokenHash: sha256Hex(pollToken),
        tvInstallId: tv.installId,
        tvName: tv.name.slice(0, 60),
        tvManufacturer: tv.manufacturer?.slice(0, 60) ?? null,
        tvModel: tv.model?.slice(0, 60) ?? null,
        tvOsVersion: tv.osVersion?.slice(0, 30) ?? null,
        tvAppVersion: tv.appVersion?.slice(0, 30) ?? null,
        claimedByUserId: null,
        tokensEnc: null,
        expiresAt: new Date(now.getTime() + this.config.pairing.ttlSeconds * 1000),
      },
    });
    return {
      pairingId: session.id,
      code,
      // The QR carries only the code, never the poll token.
      qrPayload: `videobridge://pair?c=${code}`,
      pollToken,
      expiresAt: session.expiresAt.toISOString(),
      // TV clocks are often wrong, so the TV counts down from this instead of comparing dates.
      expiresInSeconds: this.config.pairing.ttlSeconds,
      pollIntervalMs: POLL_INTERVAL_MS,
    };
  }

  private isExpired(session: PairingSession): boolean {
    return session.expiresAt.getTime() <= this.clock.now().getTime();
  }

  /** Polled by the TV. Returns the sign-in once, on the first call after approval. */
  async status(
    id: string,
    pollToken: string | undefined,
  ): Promise<{ status: string; auth?: PairingAuth }> {
    const session = await this.prisma.pairingSession.findUnique({ where: { id } });
    // A wrong token looks exactly like a missing session.
    if (
      !session ||
      !pollToken ||
      !timingSafeEqualHex(sha256Hex(pollToken), session.pollTokenHash)
    ) {
      throw new AppError('NOT_FOUND');
    }
    if ((session.status === 'PENDING' || session.status === 'CLAIMED') && this.isExpired(session)) {
      await this.prisma.pairingSession.updateMany({
        where: { id, status: session.status },
        data: { status: 'EXPIRED' },
      });
      return { status: 'EXPIRED' };
    }
    if (session.status !== 'APPROVED' || !session.tokensEnc) {
      return { status: session.status };
    }
    const consumed = await this.prisma.pairingSession.updateMany({
      where: { id, status: 'APPROVED' },
      data: { status: 'CONSUMED', consumedAt: this.clock.now(), tokensEnc: null },
    });
    if (consumed.count !== 1) {
      return { status: 'CONSUMED' };
    }
    const auth = JSON.parse(decryptSecret(session.tokensEnc, this.tokenKey)) as PairingAuth;
    return { status: 'APPROVED', auth };
  }

  /** A signed-in phone enters or scans the TV's code. */
  async claim(userId: string, deviceId: string, codeInput: string) {
    const device = await this.prisma.device.findUnique({ where: { id: deviceId } });
    if (device?.type !== 'PHONE') {
      throw new AppError('FORBIDDEN', 'Connect a TV from your phone.');
    }
    const failureKey = `pairing:claim:fail:user:${userId}`;
    const lock = await this.rateLimit.isOverLimit(failureKey, 5, TEN_MINUTES);
    if (!lock.allowed) {
      throw rateLimited(lock.retryAfterSeconds);
    }
    await this.rateLimit.enforce(`pairing:claim:user:${userId}`, 30, TEN_MINUTES);

    const code = normalizeCode(codeInput);
    // Codes repeat over time, so only the open session holding this code counts.
    const open = code
      ? await this.prisma.pairingSession.findFirst({
          where: {
            codeHash: this.hashCode(code),
            status: { in: ['PENDING', 'CLAIMED'] },
            expiresAt: { gt: this.clock.now() },
          },
          orderBy: { createdAt: 'desc' },
        })
      : null;
    if (open?.status === 'CLAIMED' && open.claimedByUserId !== userId) {
      throw new AppError('PAIRING_ALREADY_CLAIMED');
    }
    if (!open) {
      // Expired, used and unknown codes are indistinguishable to the caller.
      await this.rateLimit.consume(failureKey, 5, TEN_MINUTES);
      throw new AppError('PAIRING_CODE_INVALID');
    }
    if (open.status === 'PENDING') {
      const claimed = await this.prisma.pairingSession.updateMany({
        where: { id: open.id, status: 'PENDING' },
        data: { status: 'CLAIMED', claimedByUserId: userId, claimedAt: this.clock.now() },
      });
      if (claimed.count !== 1) {
        throw new AppError('PAIRING_ALREADY_CLAIMED');
      }
    }
    return {
      pairingId: open.id,
      tv: { name: open.tvName, manufacturer: open.tvManufacturer, model: open.tvModel },
      expiresAt: open.expiresAt.toISOString(),
    };
  }

  private async assertCanAddTv(userId: string, installId: string): Promise<void> {
    const { entitlements } = await this.entitlements.getEffective(userId);
    const devices = await this.devices.listActive(userId);
    // Re-pairing a TV that is already on the account does not use up a slot.
    const others = devices.filter((device) => device.installId !== installId);
    const tvs = others.filter((device) => device.type === 'ANDROID_TV').length;
    const check = (entitlement: 'max_tv_devices' | 'max_devices', current: number) => {
      const limit = Number(entitlements[entitlement]);
      if (current >= limit) {
        throw new AppError(
          'ENTITLEMENT_LIMIT',
          'Your plan does not allow another device. Remove one first.',
          {
            entitlement,
            limit,
            current,
          },
        );
      }
    };
    check('max_tv_devices', tvs);
    check('max_devices', others.length);
  }

  /** The claiming user confirms "Connect this TV?". Signs the TV in. */
  async approve(userId: string, id: string, name?: string) {
    const session = await this.prisma.pairingSession.findUnique({ where: { id } });
    if (!session || session.claimedByUserId !== userId || session.status !== 'CLAIMED') {
      throw new AppError('NOT_FOUND');
    }
    if (this.isExpired(session)) {
      throw new AppError('PAIRING_EXPIRED');
    }
    await this.assertCanAddTv(userId, session.tvInstallId);

    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    const device = await this.devices.registerAtLogin(userId, {
      installId: session.tvInstallId,
      type: 'ANDROID_TV',
      name: name?.trim() || session.tvName,
      manufacturer: session.tvManufacturer ?? undefined,
      model: session.tvModel ?? undefined,
      osVersion: session.tvOsVersion ?? undefined,
      appVersion: session.tvAppVersion ?? undefined,
    });
    const pair = await this.sessions.createSession({
      userId,
      deviceId: device.id,
      deviceType: 'ANDROID_TV',
      tokenVersion: user.tokenVersion,
    });
    const auth: PairingAuth = {
      accessToken: pair.accessToken,
      accessTokenExpiresAt: pair.accessTokenExpiresAt.toISOString(),
      refreshToken: pair.refreshToken,
      refreshTokenExpiresAt: pair.refreshTokenExpiresAt.toISOString(),
      user: toUserView(user),
      device: { id: device.id, type: device.type, name: device.name },
      session: { id: pair.sessionId },
    };
    const approved = await this.prisma.pairingSession.updateMany({
      where: { id, status: 'CLAIMED', claimedByUserId: userId },
      data: {
        status: 'APPROVED',
        approvedAt: this.clock.now(),
        createdDeviceId: device.id,
        tokensEnc: encryptSecret(JSON.stringify(auth), this.tokenKey),
      },
    });
    if (approved.count !== 1) {
      // Lost a race (rejected or expired meanwhile): the sign-in just created must not survive.
      await this.sessions.revoke(pair.sessionId, 'LOGOUT');
      throw new AppError('NOT_FOUND');
    }
    this.security.log({ event: 'tv_paired', userId, deviceId: device.id });
    return device;
  }

  async reject(userId: string, id: string): Promise<void> {
    const rejected = await this.prisma.pairingSession.updateMany({
      where: { id, status: 'CLAIMED', claimedByUserId: userId },
      data: { status: 'REJECTED' },
    });
    if (rejected.count !== 1) {
      throw new AppError('NOT_FOUND');
    }
  }
}
