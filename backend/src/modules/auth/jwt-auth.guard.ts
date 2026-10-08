import { CanActivate, ExecutionContext, Inject, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import type { Request } from 'express';
import { RequestWithPrincipal } from '../../common/decorators/current-principal.decorator';
import { IS_PUBLIC } from '../../common/decorators/public.decorator';
import { AppError } from '../../common/errors/app-error';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { TokenService } from './token.service';

/**
 * Global guard: every route needs a valid access token unless marked @Public().
 * A valid signature is not enough: the session must still be live, so logout and session
 * removal take effect on the very next request rather than when the token expires.
 */
@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly tokens: TokenService,
    private readonly prisma: PrismaService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (isPublic) {
      return true;
    }

    const req = context.switchToHttp().getRequest<Request & RequestWithPrincipal>();
    const [scheme, token] = (req.headers.authorization ?? '').split(' ');
    if (scheme?.toLowerCase() !== 'bearer' || !token) {
      throw new AppError('UNAUTHENTICATED');
    }
    const claims = this.tokens.verifyAccessToken(token);

    const [session, user] = await Promise.all([
      this.prisma.session.findUnique({ where: { id: claims.sessionId } }),
      this.prisma.user.findUnique({ where: { id: claims.userId } }),
    ]);
    if (
      !session ||
      session.revokedAt ||
      session.userId !== claims.userId ||
      session.expiresAt.getTime() <= this.clock.now().getTime() ||
      !user ||
      user.tokenVersion !== claims.tokenVersion
    ) {
      throw new AppError('UNAUTHENTICATED');
    }
    if (user.status !== 'ACTIVE') {
      throw new AppError('USER_SUSPENDED');
    }

    req.principal = {
      userId: claims.userId,
      deviceId: claims.deviceId,
      sessionId: claims.sessionId,
    };
    return true;
  }
}
