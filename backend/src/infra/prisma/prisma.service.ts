import { Inject, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { PrismaClient } from '@prisma/client';
import { APP_CONFIG, AppConfig } from '../../config/app-config';

const CONNECT_TIMEOUT_MS = 10_000;

@Injectable()
export class PrismaService extends PrismaClient implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(PrismaService.name);

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {
    super({ datasourceUrl: config.databaseUrl });
  }

  async onModuleInit(): Promise<void> {
    if (this.config.docsOnly) {
      return;
    }
    // Bounded: against an unreachable cluster the driver would otherwise hold startup for its
    // full server-selection timeout (30 s by default).
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('timeout')), CONNECT_TIMEOUT_MS);
    });
    // $connect alone is lazy with MongoDB: the first command pays for server discovery and
    // TLS (~1 s to Atlas). Pinging here moves that cost to startup, off the first request.
    const connecting = this.$connect().then(() => this.$runCommandRaw({ ping: 1 }));
    try {
      await Promise.race([connecting, timeout]);
    } catch {
      // Stay up and report "not ready": Prisma reconnects on the next query, and the error
      // text can contain the connection string, so it is not logged.
      connecting.catch(() => undefined);
      this.logger.error('Could not connect to MongoDB at startup; /health/ready reports it.');
    } finally {
      clearTimeout(timer);
    }
  }

  async onModuleDestroy(): Promise<void> {
    await this.$disconnect();
  }
}
