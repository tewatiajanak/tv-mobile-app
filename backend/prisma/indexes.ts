import { PrismaClient } from '@prisma/client';

// Indexes Prisma's schema cannot express for MongoDB (TTL, partial). Idempotent; runs after
// `prisma db push` (npm run prisma:migrate).
async function main(): Promise<void> {
  const prisma = new PrismaClient();
  try {
    await prisma.$runCommandRaw({
      createIndexes: 'rate_limits',
      indexes: [{ key: { expires_at: 1 }, name: 'rate_limits_ttl', expireAfterSeconds: 0 }],
    });
    // Finished or abandoned pairing sessions disappear a day after they expire.
    await prisma.$runCommandRaw({
      createIndexes: 'pairing_sessions',
      indexes: [
        { key: { expires_at: 1 }, name: 'pairing_sessions_ttl', expireAfterSeconds: 86400 },
      ],
    });
    // Codes were unique when they were 8 characters; 4-digit codes repeat, so that index goes.
    await prisma
      .$runCommandRaw({ dropIndexes: 'pairing_sessions', index: 'pairing_sessions_code_hash_key' })
      .catch(() => undefined);
    console.log('indexes ensured');
  } finally {
    await prisma.$disconnect();
  }
}

main().catch((error: unknown) => {
  console.error('ensuring indexes failed:', error instanceof Error ? error.name : error);
  process.exit(1);
});
