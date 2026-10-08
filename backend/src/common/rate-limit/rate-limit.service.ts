import { Inject, Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { AppError } from '../errors/app-error';

export interface RateLimitResult {
  allowed: boolean;
  retryAfterSeconds: number;
}

/**
 * Fixed-window counters in MongoDB (Redis is not used, ADR-0007). Each window is one document,
 * so a new window starts from zero without any cleanup; the TTL index removes old ones.
 */
@Injectable()
export class RateLimitService {
  constructor(
    private readonly prisma: PrismaService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  private window(key: string, windowSeconds: number) {
    const nowMs = this.clock.now().getTime();
    const windowMs = windowSeconds * 1000;
    const index = Math.floor(nowMs / windowMs);
    const endsAtMs = (index + 1) * windowMs;
    return {
      id: `${key}|${index}`,
      expiresAt: new Date(endsAtMs),
      retryAfterSeconds: Math.max(1, Math.ceil((endsAtMs - nowMs) / 1000)),
    };
  }

  /** Counts one event and reports whether the limit still holds. */
  async consume(key: string, limit: number, windowSeconds: number): Promise<RateLimitResult> {
    const { id, expiresAt, retryAfterSeconds } = this.window(key, windowSeconds);
    const increment = () =>
      this.prisma.rateLimit.upsert({
        where: { id },
        create: { id, count: 1, expiresAt },
        update: { count: { increment: 1 } },
      });
    let row;
    try {
      row = await increment();
    } catch (error) {
      // Two first-requests raced to create the window; the loser simply increments it.
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        row = await increment();
      } else {
        throw error;
      }
    }
    return { allowed: row.count <= limit, retryAfterSeconds };
  }

  /** Reads the counter without counting an event (e.g. "is this number locked?"). */
  async isOverLimit(key: string, limit: number, windowSeconds: number): Promise<RateLimitResult> {
    const { id, retryAfterSeconds } = this.window(key, windowSeconds);
    const row = await this.prisma.rateLimit.findUnique({ where: { id } });
    return { allowed: (row?.count ?? 0) < limit, retryAfterSeconds };
  }

  /** consume(), throwing RATE_LIMITED with `details.retryAfterSeconds` when over the limit. */
  async enforce(key: string, limit: number, windowSeconds: number): Promise<void> {
    const result = await this.consume(key, limit, windowSeconds);
    if (!result.allowed) {
      throw rateLimited(result.retryAfterSeconds);
    }
  }
}

export function rateLimited(retryAfterSeconds: number): AppError {
  return new AppError('RATE_LIMITED', undefined, { retryAfterSeconds });
}
