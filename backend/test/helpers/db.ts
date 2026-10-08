import type { NestExpressApplication } from '@nestjs/platform-express';
import { PrismaService } from '../../src/infra/prisma/prisma.service';

/** Empties the collections a spec writes to. Refuses to run against anything but a test database. */
export async function resetDatabase(app: NestExpressApplication): Promise<void> {
  if (!/_test(\?|$)/.test(process.env.DATABASE_URL ?? '')) {
    throw new Error('resetDatabase: DATABASE_URL does not name a *_test database');
  }
  const prisma = app.get(PrismaService);
  await prisma.video.deleteMany();
  await prisma.pairingSession.deleteMany();
  await prisma.session.deleteMany();
  await prisma.device.deleteMany();
  await prisma.user.deleteMany();
  await prisma.rateLimit.deleteMany();
}
