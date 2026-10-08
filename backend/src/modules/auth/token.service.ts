import { Inject, Injectable } from '@nestjs/common';
import jwt, { JwtPayload, TokenExpiredError } from 'jsonwebtoken';
import { AppError } from '../../common/errors/app-error';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { CLOCK, Clock } from '../../infra/clock/clock';

const ISSUER = 'videobridge';
const AUDIENCE = 'videobridge-app';

export interface AccessClaims {
  userId: string;
  deviceId: string;
  sessionId: string;
  tokenVersion: number;
}

@Injectable()
export class TokenService {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  signAccessToken(claims: AccessClaims): { token: string; expiresAt: Date } {
    const nowSeconds = Math.floor(this.clock.now().getTime() / 1000);
    const exp = nowSeconds + this.config.auth.accessTtlSeconds;
    const token = jwt.sign(
      {
        sub: claims.userId,
        did: claims.deviceId,
        sid: claims.sessionId,
        ver: claims.tokenVersion,
        typ: 'access',
        iat: nowSeconds,
        exp,
        iss: ISSUER,
        aud: AUDIENCE,
      },
      this.config.auth.jwtAccessSecret,
      { algorithm: 'HS256' },
    );
    return { token, expiresAt: new Date(exp * 1000) };
  }

  /** Throws TOKEN_EXPIRED for an expired token and UNAUTHENTICATED for anything else invalid. */
  verifyAccessToken(token: string): AccessClaims {
    let payload: JwtPayload;
    try {
      const decoded = jwt.verify(token, this.config.auth.jwtAccessSecret, {
        // Pinned: a token signed with another algorithm (or "none") is rejected.
        algorithms: ['HS256'],
        issuer: ISSUER,
        audience: AUDIENCE,
        clockTimestamp: Math.floor(this.clock.now().getTime() / 1000),
      });
      if (typeof decoded === 'string') {
        throw new AppError('UNAUTHENTICATED');
      }
      payload = decoded;
    } catch (error) {
      throw new AppError(error instanceof TokenExpiredError ? 'TOKEN_EXPIRED' : 'UNAUTHENTICATED');
    }
    const { sub, did, sid, ver, typ } = payload as JwtPayload & Record<string, unknown>;
    if (
      typ !== 'access' ||
      typeof sub !== 'string' ||
      typeof did !== 'string' ||
      typeof sid !== 'string' ||
      typeof ver !== 'number'
    ) {
      throw new AppError('UNAUTHENTICATED');
    }
    return { userId: sub, deviceId: did, sessionId: sid, tokenVersion: ver };
  }
}
