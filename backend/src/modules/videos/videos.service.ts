import { Inject, Injectable } from '@nestjs/common';
import { Video } from '@prisma/client';
import { AppError } from '../../common/errors/app-error';
import { defaultTitle, normalizeUrl } from '../../common/url/normalize-url';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { newId } from '../../infra/ids/ids';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { EntitlementsService } from '../entitlements/entitlements.module';

const LIST_LIMIT = 500;

/** Every method takes the authenticated userId first and filters by it: no cross-user access. */
@Injectable()
export class VideosService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly entitlements: EntitlementsService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /**
   * Saves a link. Sending the same client-generated id again returns the existing video, so a
   * retry after a lost response does not create a second copy.
   */
  async create(
    userId: string,
    deviceId: string,
    input: {
      id?: string;
      sourceUrl: string;
      title?: string;
      sizeBytes?: number;
      format?: string;
      thumbnailUrl?: string;
    },
  ): Promise<{ video: Video; created: boolean }> {
    const url = normalizeUrl(input.sourceUrl);
    if (!url) {
      throw new AppError('URL_NOT_ALLOWED');
    }
    if (input.id) {
      const sameId = await this.prisma.video.findUnique({ where: { id: input.id } });
      if (sameId) {
        if (sameId.userId === userId && sameId.urlHash === url.urlHash && !sameId.deletedAt) {
          return { video: sameId, created: false };
        }
        throw new AppError('CONFLICT', 'That id is already in use.');
      }
    }
    const duplicate = await this.prisma.video.findFirst({
      where: { userId, urlHash: url.urlHash, deletedAt: null },
    });
    if (duplicate) {
      throw new AppError('DUPLICATE_VIDEO', undefined, { existingVideoId: duplicate.id });
    }
    const { entitlements } = await this.entitlements.getEffective(userId);
    const limit = Number(entitlements.max_saved_links);
    const current = await this.prisma.video.count({ where: { userId, deletedAt: null } });
    if (current >= limit) {
      throw new AppError(
        'ENTITLEMENT_LIMIT',
        `Your plan allows ${limit} saved videos. Delete one to add another.`,
        {
          entitlement: 'max_saved_links',
          limit,
          current,
        },
      );
    }
    const title = input.title?.trim() || defaultTitle(url);
    const video = await this.prisma.video.create({
      data: {
        id: input.id ?? newId(),
        userId,
        title: title.slice(0, 200),
        sourceUrl: url.sourceUrl,
        normalizedUrl: url.normalizedUrl,
        urlHash: url.urlHash,
        sourceDomain: url.sourceDomain,
        createdFromDeviceId: deviceId,
        sizeBytes: input.sizeBytes === undefined ? null : BigInt(input.sizeBytes),
        format: input.format?.toUpperCase() ?? null,
        thumbnailUrl: input.thumbnailUrl ?? null,
        deletedAt: null,
      },
    });
    return { video, created: true };
  }

  list(userId: string): Promise<Video[]> {
    return this.prisma.video.findMany({
      where: { userId, deletedAt: null },
      orderBy: { createdAt: 'desc' },
      take: LIST_LIMIT,
    });
  }

  private async updateOwn(userId: string, id: string, data: Partial<Video>): Promise<void> {
    const result = await this.prisma.video.updateMany({
      where: { id, userId, deletedAt: null },
      data,
    });
    if (result.count !== 1) {
      // Someone else's video and a missing one look the same.
      throw new AppError('NOT_FOUND');
    }
  }

  async rename(userId: string, id: string, title: string): Promise<Video> {
    await this.updateOwn(userId, id, { title });
    return this.prisma.video.findUniqueOrThrow({ where: { id } });
  }

  /** Soft delete; deleting twice is not an error. */
  async remove(userId: string, id: string): Promise<void> {
    await this.prisma.video.updateMany({
      where: { id, userId, deletedAt: null },
      data: { deletedAt: this.clock.now() },
    });
  }

  async savePosition(
    userId: string,
    id: string,
    positionMs: number,
    durationMs?: number,
  ): Promise<void> {
    await this.updateOwn(userId, id, {
      positionMs,
      ...(durationMs === undefined ? {} : { durationMs }),
      lastPlayedAt: this.clock.now(),
    });
  }
}
