import { Inject, Injectable } from '@nestjs/common';
import { AppError } from '../../common/errors/app-error';
import { Device, DeviceType } from '@prisma/client';
import { CLOCK, Clock } from '../../infra/clock/clock';
import { newId } from '../../infra/ids/ids';
import { PrismaService } from '../../infra/prisma/prisma.service';

export interface DeviceInfo {
  installId: string;
  type: DeviceType;
  name: string;
  manufacturer?: string;
  model?: string;
  osVersion?: string;
  appVersion?: string;
}

/** Control characters out, whitespace collapsed: device names come from the client. */
function clean(value: string | undefined, max: number): string | undefined {
  const cleaned = value
    ?.replace(/\p{Cc}/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned ? cleaned.slice(0, max) : undefined;
}

@Injectable()
export class DevicesService {
  constructor(
    private readonly prisma: PrismaService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /**
   * The device row for this app install: the user's active one is reused and refreshed,
   * otherwise a new row is created (a revoked device never comes back to life).
   */
  async registerAtLogin(userId: string, info: DeviceInfo): Promise<Device> {
    const data = {
      type: info.type,
      name: clean(info.name, 60) ?? (info.type === 'ANDROID_TV' ? 'Android TV' : 'Phone'),
      manufacturer: clean(info.manufacturer, 60) ?? null,
      model: clean(info.model, 60) ?? null,
      osVersion: clean(info.osVersion, 30) ?? null,
      appVersion: clean(info.appVersion, 30) ?? null,
      lastSeenAt: this.clock.now(),
    };
    const existing = await this.prisma.device.findFirst({
      where: { userId, installId: info.installId, revokedAt: null },
    });
    if (existing) {
      return this.prisma.device.update({ where: { id: existing.id }, data });
    }
    return this.prisma.device.create({
      // revokedAt is an explicit null so `revokedAt: null` filters match it (ADR-0006).
      data: { id: newId(), userId, installId: info.installId, revokedAt: null, ...data },
    });
  }

  listActive(userId: string): Promise<Device[]> {
    return this.prisma.device.findMany({
      where: { userId, revokedAt: null },
      orderBy: { createdAt: 'asc' },
    });
  }

  /** Throws NOT_FOUND for a device that is not this user's (its existence is not revealed). */
  async rename(userId: string, deviceId: string, name: string): Promise<Device> {
    const result = await this.prisma.device.updateMany({
      where: { id: deviceId, userId, revokedAt: null },
      data: { name: clean(name, 60) ?? name },
    });
    if (result.count !== 1) {
      throw new AppError('NOT_FOUND');
    }
    return this.prisma.device.findUniqueOrThrow({ where: { id: deviceId } });
  }

  /** Marks the device removed and ends its sessions, so its tokens stop working at once. */
  async revoke(userId: string, deviceId: string): Promise<void> {
    const now = this.clock.now();
    const result = await this.prisma.device.updateMany({
      where: { id: deviceId, userId, revokedAt: null },
      data: { revokedAt: now },
    });
    if (result.count !== 1) {
      throw new AppError('NOT_FOUND');
    }
    await this.prisma.session.updateMany({
      where: { deviceId, revokedAt: null },
      data: { revokedAt: now, revokeReason: 'DEVICE_REMOVED' },
    });
  }
}
