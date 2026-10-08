# Phase 11 — Real-Time Synchronization (Robust)

> **Implement this phase only. Do not start Phase 12. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-11-realtime-sync.md
and the latest phase report. We are implementing Phase 11 (Real-Time Synchronization) only.
Inspect SyncService, the realtime gateway, every place that records changes, Android SyncManager,
outboxes and event decoding. Give me a numbered plan: event catalogue + JSON schemas + contract
tests, server-side field-level merge, mutation idempotency, device commands (phone → TV), ACKs and
retention, reorder buffer and gap handling on clients, multi-entity snapshot, backpressure,
observability, the sync simulator/fuzz test, and a sync diagnostics screen. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 11 step by step, keeping the Phase 4 protocol backwards compatible during the
change (old clients must keep working until updated). Contract tests and the sync simulator come
early so every later step is verified by them. Build, test and commit after each step.
```

**Prompt C — verify & report**
```
Run all tests including the sync simulator with at least 3 devices × 2,000 random operations with
network drops, duplicates and reordering, and show that all replicas converge to the server state.
Demonstrate phone → TV "Download on TV" while the TV is offline then online. Tick acceptance criteria,
write docs/phase-reports/PHASE-11-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failing seed / divergence report>. Reproduce with the same simulator seed, root-cause, fix,
add the seed as a regression test, run the full suite, commit.
```

---

## 1. Goal

Every device converges to the same, correct state — quickly when online, reliably after being
offline — with no duplicates, no lost edits, predictable conflict handling, and the phone able to
**command the TV** (download, pause, resume, cancel) even when the TV is temporarily offline.

## 2. Prerequisites
Phase 4 (per-user `seq`, `sync_events`, `/sync/changes`, `HELLO` replay), Phases 5–8 events, Phase 9/10 UIs.

## 3. Scope
**In:** complete persisted event catalogue, versioned event schemas + contract tests, all
entities routed through `SyncService`, server-side field-level merge, mutation idempotency,
device commands, client ACKs + retention policy, client reorder buffer/gap handling, multi-entity
snapshot, backpressure, observability, sync simulator, diagnostics screen, phone download controls.
**Out:** notifications (14), queue features (12).

---

## 4. Event catalogue (final)

| Event | Persisted | Producer | Data |
|---|---|---|---|
| `VIDEO_CREATED` / `VIDEO_UPDATED` | ✔ | server (from any device) | full video DTO incl. `version`, `fieldVersions` |
| `VIDEO_DELETED` | ✔ | server | `{videoId, version}` |
| `VIDEO_METADATA_UPDATED` | ✔ | server | `{videoId, metadata}` |
| `PLAYBACK_UPDATED` | ✔ (final saves only) | server | `{videoId, positionMs, durationMs, completed, deviceId, updatedAt}` |
| `DOWNLOAD_REQUESTED` | ✔ | phone → server → TV | `{commandId, targetDeviceId, videoId, storageLocationId?, priority}` |
| `DEVICE_COMMAND` | ✔ | phone → server → TV | `{commandId, targetDeviceId, type: PAUSE_DOWNLOAD|RESUME_DOWNLOAD|CANCEL_DOWNLOAD|RETRY_DOWNLOAD, jobId}` |
| `DEVICE_COMMAND_RESULT` | ✔ | TV → server → phone | `{commandId, status: ACCEPTED|REJECTED|EXPIRED, reasonCode?, jobId?}` |
| `DOWNLOAD_STARTED` / `DOWNLOAD_COMPLETED` / `DOWNLOAD_FAILED` / `DOWNLOAD_STATE_CHANGED` | ✔ | TV → server | `{job}` |
| `DOWNLOAD_PROGRESS` | ✘ ephemeral, coalesced 1/s/job | TV → server | `{jobId, bytes, totalBytes, speedBps, etaMs}` |
| `DEVICE_CONNECTED` / `DEVICE_UPDATED` / `DEVICE_REMOVED` | ✔ | server | device DTO / `{deviceId}` |
| `DEVICE_ONLINE` / `DEVICE_OFFLINE` | ✘ | server | `{deviceId}` |
| `STORAGE_LOCATION_UPDATED` / `STORAGE_LOCATION_REMOVED` | ✔ | TV → server | location DTO |
| `SUBSCRIPTION_CHANGED` / `ENTITLEMENTS_CHANGED` | ✔ | server | `{subscription, entitlements}` |
| `CATEGORY_RENAMED` | ✔ | server | `{from, to, affectedCount}` |

- Schemas: `docs/api/events/<TYPE>.v1.schema.json` (JSON Schema 2020-12). Envelope `v` = protocol
  version; each `data` schema versioned separately (`dataVersion`).
- **Contract tests:** backend tests validate every emitted event against its schema; Android
  tests decode shared fixture files (`docs/api/events/fixtures/*.json`) with kotlinx.serialization.
- Forward compatibility: clients ignore unknown event types and unknown fields.

---

## 5. Server-side changes

### 5.1 Everything through `SyncService`
Audit every mutation path (videos, metadata, playback, downloads, storage, devices, subscriptions,
entitlement overrides, categories) and route it through `SyncService.recordChange(tx, …)`. Add a
test that fails if any service writes a syncable table without recording an event (e.g. a Prisma
middleware/extension that tracks writes per transaction in test mode).

### 5.2 Field-level merge for user-editable entities (videos)
- Add `field_versions jsonb` to `videos`: `{ "title": {"v": 7, "at": "...", "by": "<deviceId>"}, "notes": {...}, "category": {...}, "description": {...} }`.
- `PATCH /videos/{id}` body now: `{ mutationId, baseVersion, fields: { title?: {value, editedAt}, … } }`
  (the Phase 4 body shape remains accepted for old clients: treated as all fields edited at
  receipt time with `baseVersion = version`).
- Merge rule per field: if the field's server `v ≤ baseVersion` → apply (no concurrent change);
  else concurrent change → apply only if `editedAt` (clamped to `[serverNow − 7 d, serverNow]`) is
  newer than the server field's `at`; otherwise keep the server value and report it in
  `result.rejectedFields`. Result: no 409 for disjoint edits; deterministic LWW per field.
- **Delete wins:** edits to a deleted video return `410 GONE` (client drops its pending edit).
  No resurrection by edits; re-adding the URL creates a new video.
- Playback: LWW by `updatedAt` (client time clamped as above) to tolerate out-of-order arrival.
- Downloads and storage: **single writer** (the owning TV); others only send commands.

### 5.3 Mutation idempotency
- Every client mutation carries `mutationId` (UUIDv7). Server stores processed ids in
  `client_mutations(user_id, mutation_id pk, response_hash, created_at)` (or Redis 48 h for
  non-create mutations) and returns the original result on replay. Creates remain idempotent on entity id.

### 5.4 Device commands (phone → TV)
- Table `device_commands(id, user_id, issuer_device_id, target_device_id, type, payload jsonb,
  status PENDING|DELIVERED|ACCEPTED|REJECTED|EXPIRED|CANCELLED, reason_code, created_at, delivered_at,
  resolved_at, expires_at)`; default expiry 24 h.
- `POST /api/v1/devices/{tvId}/commands` `{ id, type, payload }` (phone only; TV must belong to
  user and be active) → 202 + `DOWNLOAD_REQUESTED`/`DEVICE_COMMAND` event targeted at the TV.
  Entitlement checks happen on the TV when it enqueues (it calls `POST /downloads`, Phase 6), and
  failures come back as `REJECTED` with `reasonCode` (e.g. `ENTITLEMENT_LIMIT`, `NOT_DOWNLOADABLE`, `NO_STORAGE`).
- `POST /api/v1/devices/commands/{id}/result` (target TV only) → `DEVICE_COMMAND_RESULT`.
- `GET /api/v1/devices/commands?targetDeviceId=me&status=PENDING` (TV fetches on reconnect, in addition to replay).
- Cron: expire stale commands, emit results.

### 5.5 ACKs, retention, snapshot
- Clients send `ACK {seq}` after applying (debounced 2 s). Store `device_sync_state(device_id pk,
  acked_seq, acked_at)`. Retention: keep events ≥ min(acked_seq of devices active in the last 30
  days) **or** 30 days, whichever retains more, capped at 90 days. Older → `RESYNC_REQUIRED`.
- `GET /api/v1/sync/snapshot?entities=videos,metadata,playback,downloads,devices,storage,entitlements&cursor=`
  → consistent pages (REPEATABLE READ transaction per page + the `serverSeq` captured at start;
  client then catches up from that seq).
- `WELCOME {deviceId, serverSeq, serverTime, protocol: 2}`; clients use `serverTime` for skew
  display only, never for ordering.

### 5.6 Delivery robustness
- Publish after commit; Redis pub/sub loss is tolerated because clients detect gaps by `seq`.
- Backpressure: if a socket's `bufferedAmount` > 1 MB or the send queue > 500 events → close
  with `1013` (try again later); the client reconnects and catches up via REST.
- `DOWNLOAD_PROGRESS` coalesced server-side per job (latest wins, flush every 1 s).
- Rate limits on inbound WS messages (per device: 20 msg/s burst 50).

### 5.7 Observability
Prometheus-style metrics (exposed for Phase 17): `ws_connections`, `ws_messages_in/out_total{type}`,
`sync_events_recorded_total{type}`, `sync_catchup_requests_total`, `sync_resync_required_total`,
`sync_replay_events_total`, `device_commands_total{type,status}`, `event_publish_lag_ms` histogram.

---

## 6. Android changes

### 6.1 SyncManager v2 (`core:data`)
- **Reorder buffer:** if `seq > lastSeq + 1`, hold the event up to 500 ms waiting for the missing
  ones; then call `/sync/changes`. Duplicates (`seq ≤ lastSeq`) ignored.
- Single apply pipeline (Mutex) for WS events, catch-up pages and snapshot pages; each event applied
  idempotently inside one Room transaction together with `lastSeq`.
- Handlers per event type (registry pattern), each with unit tests; unknown types skipped but `seq` advanced.
- ACK sender (debounced). `RESYNC_REQUIRED` → snapshot all entities while keeping pending outbox rows.
- Outbox v2: mutations carry `mutationId`, `baseVersion` and per-field `editedAt`; handles
  `rejectedFields` (show "Title was changed on another device" once) and `410 GONE` (drop).
- Connectivity & lifecycle triggers unchanged; TV keeps the socket open while downloads are active
  or commands may arrive (foreground only otherwise — TVs are mostly foreground anyway).

### 6.2 TV command executor
- Receives `DOWNLOAD_REQUESTED`/`DEVICE_COMMAND` (WS or pending list), validates (video synced?
  downloadable? storage available? entitlements?), executes via `DownloadRepository`, posts the
  result. Idempotent by `commandId` (Room table `processed_commands`). If the video isn't synced
  yet, fetch it first.
- Shows a TV toast: "Download requested from Rahul's phone: <title>".

### 6.3 Phone download controls
- Video detail / Library item: **Download on TV** → choose TV (online state shown) → optional
  storage location (from that TV's locations) → command sent → status "Sent · waiting for TV" →
  "Downloading on Living Room TV" when `DEVICE_COMMAND_RESULT ACCEPTED` + `DOWNLOAD_STARTED` arrive;
  rejection reasons shown in plain words.
- Downloads screen gains Pause / Resume / Cancel / Retry buttons per job (commands).
- Commands to offline TVs are allowed; the UI says they'll run when the TV comes online (expires in 24 h).

### 6.4 Sync diagnostics (Settings → About → tap version 7× in non-prod; always available in debug)
Shows `lastSeq`, `serverSeq`, connection state, last catch-up, pending outbox count, last error,
buttons: Force catch-up, Force resync.

---

## 7. Sync simulator & fuzz testing (`tools/sync-sim/`)

A TypeScript harness that runs against a real backend (test compose):
- Creates a user and N simulated devices (phones + one TV) speaking the real REST + WS protocol.
- Random operations with a seeded PRNG: create/edit/delete videos (concurrent edits to the same
  fields), playback saves, TV download state changes, phone commands, device rename.
- Chaos: random disconnects, offline periods, duplicated and reordered deliveries (a proxy layer in
  the sim), server restart in the middle (optional), Redis pub/sub message drops (sim filter).
- At the end: all devices reconnect and catch up; assert every device's local model equals the
  server snapshot (videos, metadata, playback, downloads, devices) and no mutation was applied twice.
- Run in CI with a fixed small config (e.g. 3 devices × 300 ops) and nightly with a large one.
- Android side: a JVM test drives `SyncManager` with a scripted fake server (reorder, dupes, gaps, 410).

---

## 8. File structure (new/changed)
```
docs/api/events/*.v1.schema.json  docs/api/events/fixtures/*.json
backend/src/modules/sync/{sync.service.ts,merge/field-merge.ts,idempotency.service.ts,snapshot.controller.ts,ack.handler.ts,retention.cron.ts,metrics.ts,contract.spec.ts}
backend/src/modules/devices/commands/{commands.controller.ts,commands.service.ts,commands.cron.ts}
backend/src/modules/realtime/{backpressure.ts,progress-coalescer.ts}
backend/prisma/migrations/…_sync_v2 (field_versions, client_mutations, device_commands, device_sync_state)
tools/sync-sim/{package.json,src/*.ts,README.md}
android/core/data/…/sync/{SyncManagerV2.kt,ReorderBuffer.kt,EventHandlers/*.kt,AckSender.kt,SnapshotLoader.kt}
android/tv/download/…/commands/{CommandExecutor.kt,ProcessedCommandDao.kt}
android/app-phone/…/downloads/{DownloadOnTvSheet.kt,DownloadControls.kt}
android/app-*/…/diagnostics/SyncDiagnosticsScreen.kt
docs/architecture.md (Sync v2 section with sequence diagrams)
```

## 9. Acceptance criteria
- [ ] All events in §4 implemented with schemas; contract tests pass on both sides.
- [ ] No syncable write bypasses `SyncService` (enforced by test).
- [ ] Concurrent edits to different fields merge; same-field conflicts resolve deterministically (per-field LWW); deletes win.
- [ ] Mutation replays never double-apply.
- [ ] Gaps, duplicates and reordering handled (unit + simulator); `RESYNC_REQUIRED` path works without losing pending changes.
- [ ] Phone → TV commands work online and offline-then-online, with clear results and expiry.
- [ ] Backpressure and progress coalescing in place; metrics exposed.
- [ ] Simulator: 3 devices × 2,000 ops with chaos converges (seed recorded in report); CI small run green.
- [ ] Old (Phase 4 protocol) client still works against the new server (compat test).
- [ ] All tests pass; architecture doc updated.

## 10. Manual test script
1. Phone and TV online: edit a title on the phone and notes on the TV at the same time → both changes survive on both.
2. Turn the TV off (or disconnect) → on the phone "Download on TV" → status "waiting for TV" → turn the TV on → download starts → phone shows ACCEPTED then progress.
3. Pause/resume/cancel the TV download from the phone.
4. Airplane mode on the phone for 10 min while editing → reconnect → everything merges.
5. Diagnostics screen shows seqs moving; Force resync works without losing a pending edit.

## 11. Pitfalls
- Don't trust device clocks for ordering — clamp `editedAt` and use `seq` for event order.
- Publishing inside transactions or before commit creates phantom events — keep publish after commit.
- A reorder buffer without a timeout stalls sync forever — always fall back to REST catch-up.
- Keep handlers idempotent; the same event will be applied more than once in real life.
