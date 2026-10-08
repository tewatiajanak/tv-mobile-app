import { Controller, Get, Global, Injectable, Module } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiTags } from '@nestjs/swagger';

/** FREE-plan defaults. Phase 8 replaces the data source (plans in the database), not the callers. */
export const DEFAULT_ENTITLEMENTS = {
  max_devices: 2,
  max_tv_devices: 1,
  max_saved_links: 50,
  max_active_downloads: 1,
  max_queued_downloads: 3,
  max_storage_destinations: 1,
  scheduled_downloads: false,
  bandwidth_controls: false,
  family_profiles: false,
  priority_support: false,
  advanced_playback: false,
  download_history: false,
} as const;

export type Entitlements = { [K in keyof typeof DEFAULT_ENTITLEMENTS]: number | boolean };

export interface EffectiveEntitlements {
  plan: string;
  entitlements: Entitlements;
  source: 'default';
}

/** Every limit check goes through this service, so no limit is hardcoded in a feature. */
@Injectable()
export class EntitlementsService {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  getEffective(_userId: string): Promise<EffectiveEntitlements> {
    return Promise.resolve({
      plan: 'FREE',
      entitlements: { ...DEFAULT_ENTITLEMENTS },
      source: 'default',
    });
  }
}

@ApiTags('entitlements')
@ApiBearerAuth()
@Controller('entitlements')
class EntitlementsController {
  constructor(private readonly entitlements: EntitlementsService) {}

  @Get()
  @ApiOperation({ summary: 'The limits and features of the signed-in account.' })
  get(): Promise<EffectiveEntitlements> {
    return this.entitlements.getEffective('');
  }
}

@Global()
@Module({
  controllers: [EntitlementsController],
  providers: [EntitlementsService],
  exports: [EntitlementsService],
})
export class EntitlementsModule {}
