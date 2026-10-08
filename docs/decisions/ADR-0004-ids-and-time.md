# ADR-0004 — Identifiers and time

**Status:** Accepted (Phase 1)

## Context
Phones create videos offline and TVs create download jobs offline; both must be able to retry a
create safely. Devices' clocks, especially TVs', are often wrong.

## Decision
- **IDs are UUIDv7** everywhere (stored as strings in MongoDB `_id`, see ADR-0006). They are time-ordered, so they index well and
  sort by creation. The backend generates them with `newId()`; clients generate them for entities
  they create offline, which makes creates idempotent on `(userId, id)`.
- **Time is UTC.** BSON dates in the database; JSON uses ISO-8601 with `Z` and millisecond precision.
  Durations are integer milliseconds (`…Ms`), sizes integer bytes (`…Bytes`).
- Server time is authoritative. Time-dependent backend logic takes an injected `Clock` so it can
  be tested with `FakeClock`.

## Consequences
- A UUIDv7 reveals roughly when its entity was created; acceptable for these entities.
- Client-supplied IDs must be validated as UUIDs and checked for cross-user collisions.
- Ordering of synced events uses the server sequence (Phase 4), never device timestamps.
