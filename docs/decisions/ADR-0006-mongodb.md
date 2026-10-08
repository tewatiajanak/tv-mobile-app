# ADR-0006 — MongoDB instead of PostgreSQL

**Status:** Accepted (2026-10-08, owner's decision). Supersedes the database choice in ADR-0002
and in `docs/phases/00-architecture-and-conventions.md` §3 and §8.

## Context
The phase pack specifies PostgreSQL 16. The owner wants a hosted MongoDB (Atlas), which the
team already uses elsewhere, and has no working Docker on the development machine. The trade-offs
were stated before the decision: the pack's later phases lean on PostgreSQL features, so each
needs a MongoDB equivalent.

## Decision
- **MongoDB (Atlas)** is the database. It must be a replica set (Atlas always is), because
  multi-document transactions require one.
- **Prisma stays** as the data layer (`provider = "mongodb"`), so the Prisma models in the phase
  files carry over with small edits and services keep one query API.
- Development and e2e tests use separate databases on the cluster: `videobridge` and
  `videobridge_test`. Staging and production get their own clusters (Phase 17).

## How the pack's PostgreSQL rules map

| Pack says | With MongoDB |
|---|---|
| `prisma migrate dev/deploy`, migration folders, "applies on empty DB and on previous phase DB" | No migration files. `prisma db push` syncs collections and indexes. Data changes that need a backfill are written as idempotent scripts in `backend/prisma/scripts/` and listed in the phase report. |
| `id uuid pk`, `@db.Uuid` | `id String @id @map("_id")` holding a UUIDv7 string from `newId()`. Not ObjectId: ids are client-generated in places and must match across REST, events and Room. |
| `timestamptz`, `@db.Timestamptz` | `DateTime` (BSON date, UTC, millisecond precision). |
| `BigInt` columns (bytes, ms, seq) | `BigInt` (BSON int64). |
| Postgres enums | Prisma enums (stored as strings). |
| `created_at default now()` | `@default(now())` is applied by Prisma, not the database. |
| `SELECT … FOR UPDATE` on a row to make count-then-insert race-free (device limits, link limits, OTP attempts, per-user `sync_seq`) | A transaction whose **first write is an atomic `$inc` on the owning document** (e.g. the user's `syncSeq` or a counter field). Concurrent transactions touching the same document conflict and are retried; wrap in a small `withTransactionRetry` helper. Counters that must never exceed a limit use a conditional update (`updateMany` with the limit in the filter) and check `count === 1`. |
| Partial unique index (`WHERE revoked_at IS NULL`, `WHERE deleted_at IS NULL`, one live subscription per user) | Prisma can't declare these. Create them with `partialFilterExpression` in `backend/prisma/indexes.ts` (run after `db push`). Partial filters can't use `$ne`/`$in`-style negation freely, so prefer an explicit boolean or status field (`isActive: true`) in the filter. |
| `citext` (case-insensitive unique, admin email) | Store a normalised lower-case copy and index that. |
| `pg_trgm` GIN search on videos | Phase 4: a case-insensitive regex over a normalised `searchText` field, scoped by `userId` (libraries are small per user). Atlas Search is the upgrade path if that gets slow. |
| `jsonb` | `Json`. |
| Relations and foreign keys | Prisma relations work, but MongoDB enforces nothing: no cascades, no FK errors. Deletes that must cascade are written explicitly. |
| Audit log: trigger rejecting `UPDATE`/`DELETE`, app role with `INSERT, SELECT` only (Phase 13) | An Atlas database user for the app whose custom role grants only `insert` and `find` on `audit_logs`; the hash chain still detects tampering. |
| Separate `vb_migrator` / `vb_app` DB users (Phase 17) | Two Atlas users: one with `readWrite` + index management for `db push`, one with `readWrite` for the app. |
| `REPEATABLE READ` snapshot pages (Phase 11) | A transaction with `snapshot` read concern, or pages bounded by the captured `serverSeq`. |
| `EXPLAIN ANALYZE`, PgBouncer, PITR, `pg_dump` (Phases 16–17) | `explain("executionStats")`, the driver's pool, Atlas continuous backup, `mongodump`. |
| Readiness `SELECT 1` | `{ ping: 1 }`. |
| Prisma errors `P2002` → `DUPLICATE`, `P2025` → `NOT_FOUND` | Unchanged. |

**Nullable fields used in filters must be written as explicit `null` on create.** In MongoDB a
`{ revokedAt: null }` filter through Prisma matches documents where the field *is* null, not
ones where it is missing, and Prisma omits unset optional fields. Without the explicit null,
"revoke all live sessions" silently matches nothing (found in Phase 2 by the e2e tests).

SQL naming rules (plural `snake_case` tables and columns via `@@map`/`@map`) are kept for
collection and field names, so documents read the same as the pack's tables.

## Consequences
- No Docker is needed for development: the database is hosted.
- Every phase from 2 on must translate its data-model and concurrency sections through the table
  above before implementation, and say so in its plan. Phases 2, 4, 8 and 13 are the heaviest.
- Integrity that PostgreSQL enforced (foreign keys, check constraints, triggers) moves into
  application code and tests.
- A free Atlas tier caps connections and has no dedicated resources; load targets in Phase 16
  need a paid tier.
- Before any move from Prisma 6 to 7 (see ADR-0002), confirm that Prisma 7 supports the MongoDB
  connector; early 7.x releases did not.
- On this development Mac the `mongodb+srv://` form fails inside Prisma (it cannot parse the
  machine's `/etc/resolv.conf`), so `.env` uses the standard multi-host form of the same URI.
