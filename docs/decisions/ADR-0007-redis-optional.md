# ADR-0007 — Redis is optional

**Status:** Accepted (2026-10-08, owner's decision). Amends ADR-0002.

## Context
The phase pack uses Redis for rate limits, short-lived caches and locks, cross-instance WebSocket
fan-out, pub/sub for long-polling, and BullMQ job queues. The owner does not want to run or pay
for a Redis service.

## Decision
`REDIS_URL` is optional. When it is not set, no Redis client exists, readiness reports
`redis: "disabled"`, and that does not block readiness. Features the pack builds on Redis are
built on MongoDB or in-process instead:

| Pack uses Redis for | Without Redis |
|---|---|
| Rate limits and cooldowns (Phase 2 on) | A MongoDB collection of counters with a TTL index and atomic `$inc`. |
| Short-lived keys: revoked sessions, pairing tokens, idempotency keys, presence | MongoDB documents with TTL indexes (TTL deletion runs about once a minute, so expiry is also checked in code). |
| Entitlement and config caches | In-process cache with a short TTL. |
| WebSocket fan-out and long-poll wake-ups (Phases 3, 4, 11) | In-process event emitter. |
| BullMQ queues (Phases 5, 13, 14) | A MongoDB-backed job collection polled by the worker. |

## Consequences
- One less service to run; development needs only MongoDB.
- **The backend can run as one instance only.** In-process fan-out and caches don't reach a
  second instance, so horizontal scaling (Phase 17's scale path) requires adding Redis back.
  The `REDIS` provider and `REDIS_URL` are kept for that reason.
- Rate limiting through MongoDB costs a database write per limited request.
- Each phase that the table touches must state in its plan which substitute it uses.
