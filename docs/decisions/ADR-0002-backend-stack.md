# ADR-0002 — Backend stack

**Status:** Accepted (Phase 1)

## Context
The backend stores accounts, devices, links, metadata, entitlements and sync state, and keeps
devices in sync in real time. It must be testable against real dependencies.

## Decision
- Node.js LTS + TypeScript (strict) + NestJS, one module per domain.
- ~~PostgreSQL 16 through Prisma~~ — **superseded by ADR-0006: MongoDB through Prisma.**
- Redis 7 (ioredis) for rate limits, caches and cross-instance fan-out.
- REST under `/api/v1` documented with OpenAPI; WebSocket at `/ws` with the plain `ws` adapter
  (not socket.io) so OkHttp can speak it directly.
- Jest for unit tests; Jest + Supertest e2e tests against a real database and Redis.

## Consequences
- Plain-JSON WebSocket means we own reconnect/replay semantics (Phases 4 and 11).
- Version choices made in Phase 1, with reasons:
  - **NestJS 11, not 12.** NestJS 12 is ESM-only; the locked test stack (Jest + ts-jest) runs
    CommonJS. Staying on 11 keeps the whole backend CommonJS. Revisit when moving is cheap.
  - **Prisma 6, not 7.** Prisma 7 requires driver adapters and a new config/generator layout;
    6 supports the `PrismaService extends PrismaClient` shape the phase files assume.
  - **uuid 11** (has a CommonJS build and `v7`), **TypeScript 5.9**.
- ESLint uses the flat config (`eslint.config.mjs`); the `.eslintrc.*` format is gone in ESLint 9+.
