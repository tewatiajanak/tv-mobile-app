import { Inject, Injectable, Logger } from '@nestjs/common';
import { DeviceType, Session, SessionRevokeReason } from '@prisma/client';
import { AppError } from '../../common/errors/app-error';
import { randomToken, sha256Hex } from '../../common/utils/crypto';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { newId } from '../../infra/ids/ids';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { TokenService } from './token.service';

const DAY_MS = 24 * 60 * 60 * 1000;
const REFRESH_PREFIX = 'vbr_';

export interface TokenPair {
  accessToken: string;
  accessTokenExpiresAt: Date;
  refreshToken: string;
  refreshTokenExpiresAt: Date;
  sessionId: string;
}

@Injectable()
export class SessionService {
  private readonly logger = new Logger('security');

  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokenService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  private newRefreshToken(): { token: string; hash: string } {
    const token = `${REFRESH_PREFIX}${randomToken(32)}`;
    return { token, hash: sha256Hex(token) };
  }

  /** Sliding lifetime: every use pushes expiry out again, so an app in use never signs out. */
  private refreshExpiry(deviceType: DeviceType): Date {
    const days =
      deviceType === 'ANDROID_TV'
        ? this.config.auth.refreshTtlDaysTv
        : this.config.auth.refreshTtlDaysPhone;
    return new Date(this.clock.now().getTime() + days * DAY_MS);
  }

  /** One active session per device: signing in again replaces the previous one. */
  async createSession(input: {
    userId: string;
    deviceId: string;
    deviceType: DeviceType;
    tokenVersion: number;
    userAgent?: string;
  }): Promise<TokenPair> {
    const now = this.clock.now();
    await this.prisma.session.updateMany({
      where: { deviceId: input.deviceId, revokedAt: null },
      data: { revokedAt: now, revokeReason: 'LOGOUT' },
    });
    const refresh = this.newRefreshToken();
    const session = await this.prisma.session.create({
      data: {
        id: newId(),
        userId: input.userId,
        deviceId: input.deviceId,
        refreshTokenHash: refresh.hash,
        expiresAt: this.refreshExpiry(input.deviceType),
        lastUsedAt: now,
        userAgent: input.userAgent?.slice(0, 200),
        // Written as explicit nulls: in MongoDB a `revokedAt: null` filter does not match a
        // document where the field is missing altogether (ADR-0006).
        revokedAt: null,
        revokeReason: null,
        prevRefreshTokenHash: null,
        rotatedAt: null,
      },
    });
    return this.pair(session, input.tokenVersion, refresh.token);
  }

  private pair(session: Session, tokenVersion: number, refreshToken: string): TokenPair {
    const access = this.tokens.signAccessToken({
      userId: session.userId,
      deviceId: session.deviceId,
      sessionId: session.id,
      tokenVersion,
    });
    return {
      accessToken: access.token,
      accessTokenExpiresAt: access.expiresAt,
      refreshToken,
      refreshTokenExpiresAt: session.expiresAt,
      sessionId: session.id,
    };
  }

  /**
   * Exchanges a refresh token for a new pair. Every token works once: presenting an already
   * replaced token means it leaked (or a client bug), so the whole session is revoked. Only
   * within a short grace window is it treated as two requests from the same client racing.
   */
  async rotate(refreshToken: string): Promise<TokenPair> {
    const now = this.clock.now();
    const hash = sha256Hex(refreshToken);
    const session = await this.prisma.session.findUnique({ where: { refreshTokenHash: hash } });

    if (!session) {
      const previous = await this.prisma.session.findFirst({
        where: { prevRefreshTokenHash: hash },
      });
      if (!previous || previous.revokedAt) {
        throw new AppError(previous ? 'SESSION_REVOKED' : 'REFRESH_INVALID');
      }
      const graceMs = this.config.auth.refreshReuseGraceSeconds * 1000;
      if (previous.rotatedAt && now.getTime() - previous.rotatedAt.getTime() <= graceMs) {
        throw new AppError('REFRESH_RACE');
      }
      await this.revoke(previous.id, 'REUSE_DETECTED');
      this.logger.warn({
        event: 'refresh_reuse_detected',
        userId: previous.userId,
        deviceId: previous.deviceId,
        sessionId: previous.id,
      });
      throw new AppError('SESSION_REVOKED');
    }
    if (session.revokedAt) {
      throw new AppError('SESSION_REVOKED');
    }
    if (session.expiresAt.getTime() <= now.getTime()) {
      await this.revoke(session.id, 'EXPIRED');
      throw new AppError('REFRESH_INVALID');
    }

    const [user, device] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: session.userId } }),
      this.prisma.device.findUnique({ where: { id: session.deviceId } }),
    ]);
    if (!user || !device || device.revokedAt) {
      throw new AppError('SESSION_REVOKED');
    }
    if (user.status !== 'ACTIVE') {
      throw new AppError('USER_SUSPENDED');
    }

    const next = this.newRefreshToken();
    // The current hash is in the filter, so of two concurrent rotations only one can win.
    const updated = await this.prisma.session.updateMany({
      where: { id: session.id, refreshTokenHash: hash, revokedAt: null },
      data: {
        refreshTokenHash: next.hash,
        prevRefreshTokenHash: hash,
        rotatedAt: now,
        lastUsedAt: now,
        expiresAt: this.refreshExpiry(device.type),
      },
    });
    if (updated.count !== 1) {
      throw new AppError('REFRESH_RACE');
    }
    const rotated = await this.prisma.session.findUniqueOrThrow({ where: { id: session.id } });
    return this.pair(rotated, user.tokenVersion, next.token);
  }

  async revoke(sessionId: string, reason: SessionRevokeReason): Promise<void> {
    await this.prisma.session.updateMany({
      where: { id: sessionId, revokedAt: null },
      data: { revokedAt: this.clock.now(), revokeReason: reason },
    });
  }

  /** Revokes one of the user's own sessions; false when it is not theirs or already gone. */
  async revokeOwn(userId: string, sessionId: string): Promise<boolean> {
    const result = await this.prisma.session.updateMany({
      where: { id: sessionId, userId, revokedAt: null },
      data: { revokedAt: this.clock.now(), revokeReason: 'LOGOUT' },
    });
    return result.count === 1;
  }

  async revokeAllForUser(userId: string): Promise<void> {
    await this.prisma.session.updateMany({
      where: { userId, revokedAt: null },
      data: { revokedAt: this.clock.now(), revokeReason: 'LOGOUT_ALL' },
    });
    // Also invalidates access tokens that are still within their 15 minutes.
    await this.prisma.user.update({
      where: { id: userId },
      data: { tokenVersion: { increment: 1 } },
    });
    this.logger.log({ event: 'logout_all', userId });
  }

  async listForUser(userId: string) {
    const sessions = await this.prisma.session.findMany({
      where: { userId, revokedAt: null, expiresAt: { gt: this.clock.now() } },
      orderBy: { createdAt: 'desc' },
    });
    const devices = await this.prisma.device.findMany({
      where: { id: { in: sessions.map((session) => session.deviceId) } },
    });
    const byId = new Map(devices.map((device) => [device.id, device]));
    return sessions.map((session) => ({ session, device: byId.get(session.deviceId) }));
  }
}
