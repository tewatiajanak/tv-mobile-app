# Phase 4 — Saved Video Links (+ Realtime & Reconnect Sync)

> **Implement this phase only. Do not start Phase 5. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-04-saved-video-links.md
and the latest phase report. We are implementing Phase 4 (Saved Video Links) only.
Inspect the realtime gateway, devices, entitlements stub and Android Room setup first.
Give me a numbered plan: videos + sync_events models and migration, URL normalization,
video endpoints, per-user sequence + change feed, realtime publishing, Android Room entities and
outbox, SyncManager, phone add/share/edit/delete UI, TV library + detail, and tests per step.
Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 4 step by step: backend (schema → normalization → VideosService with sync
events → controllers → change feed → realtime fan-out → tests), then Android (Room + outbox →
repository → SyncManager → phone Add/Share/Library/Edit → TV Library/Detail → tests).
Build, test and commit after each step.
```

**Prompt C — verify & report**
```
Run the Phase 4 verification commands and all tests. Demonstrate: share a link on the phone,
it appears on the TV in under 2 seconds; edit/delete propagate both ways; turn the TV's network
off, make 3 changes on the phone, turn it back on → TV catches up. Tick acceptance criteria with
evidence, write docs/phase-reports/PHASE-04-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

The phone can save video links by **pasting** or via **Android Share** (WhatsApp, Telegram,
Chrome, YouTube app, etc.), with title, category and notes. Links appear on the **TV**
almost instantly and survive offline periods. Both devices can edit and delete; changes sync
in both directions. Search and filters work. The phone works offline (changes queue and send later).

## 2. Prerequisites
Phase 3: authenticated WebSocket + `RealtimePublisher`, devices, `EntitlementsService` stub.

## 3. Scope
**In:** `videos` table, URL normalization & duplicate detection, CRUD API, search/filter/sort,
categories, per-user `sync_seq` + `sync_events` change log, change feed endpoint, realtime
`VIDEO_*` events, `max_saved_links` enforcement, Android offline-first repository with outbox,
`SyncManager` (snapshot + catch-up + live), phone Add/Share/Edit/Delete/Library, TV
Library/Detail (Delete works; Play/Download buttons are present but hidden behind local feature
switches until Phases 5/6).
**Out:** URL inspection/metadata/thumbnails from the server (Phase 5), playback (Phase 5),
downloads (Phase 6), polished UI (Phases 9/10), full sync hardening (Phase 11).

---

## 4. Data model (migration `…_videos_sync`)

```prisma
enum VideoStatus { NEW INSPECTING READY UNSUPPORTED UNREACHABLE }

model Video {
  id                  String      @id @db.Uuid                 // client-generated UUIDv7 allowed
  userId              String      @map("user_id") @db.Uuid
  title               String      @db.VarChar(200)
  sourceUrl           String      @map("source_url") @db.VarChar(2048)
  normalizedUrl       String      @map("normalized_url") @db.VarChar(2048)
  urlHash             String      @map("url_hash") @db.Char(64)   // sha256(normalizedUrl)
  sourceDomain        String      @map("source_domain") @db.VarChar(253)
  thumbnailUrl        String?     @map("thumbnail_url") @db.VarChar(2048)
  description         String?     @db.VarChar(2000)
  category            String?     @db.VarChar(40)
  notes               String?     @db.VarChar(2000)
  durationMs          BigInt?     @map("duration_ms")
  fileSizeBytes       BigInt?     @map("file_size_bytes")
  mimeType            String?     @map("mime_type") @db.VarChar(120)
  status              VideoStatus @default(NEW)
  version             Int         @default(1)
  createdFromDeviceId String?     @map("created_from_device_id") @db.Uuid
  createdAt           DateTime    @default(now()) @map("created_at") @db.Timestamptz
  updatedAt           DateTime    @updatedAt @map("updated_at") @db.Timestamptz
  deletedAt           DateTime?   @map("deleted_at") @db.Timestamptz
  @@index([userId, createdAt(sort: Desc)])
  @@index([userId, category])
  @@map("videos")
}

model SyncEvent {
  id             String   @id @db.Uuid                // event id (dedupe key)
  userId         String   @map("user_id") @db.Uuid
  seq            BigInt
  type           String   @db.VarChar(40)             // VIDEO_CREATED …
  entityType     String   @map("entity_type") @db.VarChar(30)
  entityId       String   @map("entity_id") @db.Uuid
  payload        Json
  originDeviceId String?  @map("origin_device_id") @db.Uuid
  createdAt      DateTime @default(now()) @map("created_at") @db.Timestamptz
  @@unique([userId, seq])
  @@map("sync_events")
}
```
- `users.sync_seq BIGINT NOT NULL DEFAULT 0` added.
- Raw SQL in the migration:
  - `CREATE UNIQUE INDEX videos_user_urlhash_active ON videos(user_id, url_hash) WHERE deleted_at IS NULL;`
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm;`
    `CREATE INDEX videos_search_trgm ON videos USING gin ((lower(title) || ' ' || coalesce(lower(notes),'') || ' ' || source_domain) gin_trgm_ops);`

---

## 5. URL handling rules (`common/url/`)

`normalizeUrl(input)`:
1. Trim; must parse with WHATWG `URL`; scheme `http`/`https` only (else `URL_SCHEME_NOT_ALLOWED`);
   length ≤ 2048; no credentials (`user:pass@` → `URL_CREDENTIALS_NOT_ALLOWED`).
2. Lowercase scheme and host; IDN → punycode; remove default port; remove fragment.
3. Remove tracking params from a configurable list: `utm_*`, `fbclid`, `gclid`, `igshid`,
   `mc_cid`, `mc_eid`, `ref_src`. **Do not** reorder or remove other params (signed URLs).
4. `sourceDomain` = host without leading `www.`.

Default title if none given: decoded last path segment without extension if it looks like a
file name (`My.Holiday.2024.mp4` → `My Holiday 2024`), else `Video from <domain>`. Phase 5
improves titles from inspection.

---

## 6. Sync model (server)

Every mutation of a syncable entity runs in **one transaction**:
```sql
UPDATE users SET sync_seq = sync_seq + 1 WHERE id = $userId RETURNING sync_seq;   -- row lock serializes per user
-- mutate videos row (version = version + 1)
INSERT INTO sync_events (id, user_id, seq, type, entity_type, entity_id, payload, origin_device_id) VALUES (...);
```
After commit: `RealtimePublisher.publishToUser(userId, envelope)` (envelope `seq` = row seq).
Payload for `VIDEO_CREATED/UPDATED` = full video DTO (incl. `version`), `VIDEO_DELETED` =
`{ videoId, version }`. Implement a reusable `SyncService.recordChange(tx, userId, type, entityType, entityId, payload, originDeviceId)`
so Phases 5/6/8/11 reuse it.

Retention: cron deletes `sync_events` older than 30 days. Clients asking for `after` below the
oldest retained seq get `410 RESYNC_REQUIRED`.

---

## 7. API contract

### Videos
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/videos` | create (idempotent on `id`) |
| GET | `/api/v1/videos` | list/search/filter, cursor pagination; header `X-Sync-Seq` = user's seq at read time |
| GET | `/api/v1/videos/{id}` | one (404 if other user / deleted) |
| PATCH | `/api/v1/videos/{id}` | partial update with `version` |
| DELETE | `/api/v1/videos/{id}?version=n` | soft delete |
| GET | `/api/v1/videos/categories` | `{ items: [{ name, count }] }` |

**Create**
```json
// POST /api/v1/videos
{ "id": "0192…", "sourceUrl": "https://cdn.example.com/a/My.Holiday.2024.mp4?utm_source=wa",
  "title": null, "category": "Travel", "notes": "from Rahul", "description": null }
// 201 (or 200 if the same id already exists for this user with the same URL — idempotent replay)
{ "id": "0192…", "title": "My Holiday 2024", "sourceUrl": "https://cdn.example.com/a/My.Holiday.2024.mp4?utm_source=wa",
  "normalizedUrl": "https://cdn.example.com/a/My.Holiday.2024.mp4", "sourceDomain": "cdn.example.com",
  "thumbnailUrl": null, "description": null, "category": "Travel", "notes": "from Rahul",
  "durationMs": null, "fileSizeBytes": null, "mimeType": null, "status": "NEW", "version": 1,
  "createdFromDeviceId": "…", "createdAt": "…", "updatedAt": "…" }
```
Errors: `VALIDATION_FAILED`, `URL_SCHEME_NOT_ALLOWED`, `DUPLICATE_VIDEO` (409, `details.existingVideoId`),
`ENTITLEMENT_LIMIT` (`max_saved_links`), `ID_CONFLICT` (409 — same id used for a different URL/user).
Limit check: count of non-deleted videos with the user row locked (same transaction as the seq bump).

**List** query params: `q` (≥ 2 chars, trigram/ILIKE), `category`, `status`, `domain`,
`sort` = `createdAt_desc` (default) | `createdAt_asc` | `title_asc` | `updatedAt_desc`,
`limit` (≤ 100), `cursor` (opaque base64 of the sort key + id).

**Update** `{ "title"?, "category"?, "notes"?, "description"?, "version": 3 }` →
200 video (`version` 4) or `409 VERSION_CONFLICT` with `details.current` = server video.
Empty strings for nullable fields clear them; `title` cannot be empty.

**Delete** → 204; idempotent (deleting an already-deleted video → 204).

### Sync
- `GET /api/v1/sync/changes?after=1040&limit=500` →
  `{ "events": [envelope…], "nextAfter": 1100, "hasMore": false, "serverSeq": 1100 }`
  or `410 RESYNC_REQUIRED`.
- `GET /api/v1/sync/state` → `{ "serverSeq": 1100, "oldestAvailableSeq": 12 }`.

### WebSocket
- Client may send `HELLO { lastSeq }` after connect; server replays `seq > lastSeq` (max 1000,
  else `RESYNC_REQUIRED`) then streams live. Events from Section 6 are delivered to **all** the
  user's devices including the origin (clients use `originDeviceId` + `version` to skip echoes).

---

## 8. Backend tasks
1. Schema + migration (+ raw SQL indexes, `pg_trgm`).
2. `common/url/normalize-url.ts`, `default-title.ts` + unit tests (≥ 25 cases incl. IDN, ports,
   fragments, tracking params, signed URLs untouched, `javascript:`/`file:`/`data:` rejected).
3. `SyncModule`: `SyncService.recordChange`, `SyncController` (`/sync/changes`, `/sync/state`),
   retention cron.
4. `VideosModule`: service (create/list/get/update/delete/categories), controller, DTO mappers,
   cursor util (`common/pagination/cursor.ts`).
5. Hook `HELLO` replay into `RealtimeGateway`.
6. `LimitsService.assertCanAddVideo(userId, tx)`.
7. OpenAPI + docs (`docs/architecture.md` → "Sync model v1").

### Backend tests
- Unit: normalization, default titles, cursor encode/decode, version conflict logic.
- E2E:
  - create → list → get → patch → delete, events recorded with consecutive seqs.
  - idempotent create replay returns 200 and no extra event; same id with other URL → `ID_CONFLICT`.
  - duplicate URL (after normalization, e.g. `utm_` difference) → `DUPLICATE_VIDEO`; after delete, the URL can be saved again.
  - FREE limit 50 → 51st returns `ENTITLEMENT_LIMIT` (set limit to 3 via test config for speed).
  - concurrent creates (20 parallel) → seqs unique and gap-free.
  - stale version patch → 409 with current.
  - `/sync/changes` pagination; `after` below retention → 410.
  - search `q`, `category`, sort and cursor pagination stable across pages.
  - isolation: user B gets 404 for user A's video, never sees A's events (REST and WS).
  - WS: device 2 receives `VIDEO_CREATED` within 1 s of device 1's POST; `HELLO {lastSeq}` replays missed events.

---

## 9. Android tasks

### 9.1 Data layer
- Room (version bump + migration test):
  ```kotlin
  @Entity(tableName = "videos", indices = [Index("createdAt"), Index("category")])
  data class VideoEntity(
    @PrimaryKey val id: String, val title: String, val sourceUrl: String, val sourceDomain: String,
    val thumbnailUrl: String?, val description: String?, val category: String?, val notes: String?,
    val durationMs: Long?, val fileSizeBytes: Long?, val mimeType: String?, val status: String,
    val version: Int, val createdAt: Instant, val updatedAt: Instant,
    val localState: LocalState,            // SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE
    val pendingFieldsJson: String?          // changed fields for PENDING_UPDATE
  )
  ```
  `sync_state` key/value rows: `lastSeq`. FTS4 table `videos_fts` (title, notes, sourceDomain)
  for local search.
- `VideosRepository` (Room is the source of truth):
  - `observeVideos(query, category, sort): Flow<List<Video>>` (PENDING_DELETE hidden).
  - `addVideo(url, title?, category?, notes?)`: validate URL client-side (http/https, length),
    generate UUIDv7, insert `PENDING_CREATE`, trigger outbox.
  - `updateVideo(...)` → `PENDING_UPDATE`, `deleteVideo(id)` → `PENDING_DELETE`.
- **Outbox** `VideoOutboxWorker` (WorkManager unique work, `NetworkType.CONNECTED`,
  exponential backoff) and an immediate in-process flush when online:
  - PENDING_CREATE → POST; 201/200 → SYNCED with server fields; `DUPLICATE_VIDEO` → delete the
    local row and surface "Already in your library" (navigate to existing); `ENTITLEMENT_LIMIT`
    → keep as `FAILED_LIMIT` with a visible banner.
  - PENDING_UPDATE → PATCH with base `version`; `VERSION_CONFLICT` → **server wins** in this
    phase (apply `details.current`), show a one-time snackbar "Updated on another device".
  - PENDING_DELETE → DELETE; 204/404 → remove row.
- **`SyncManager`** (in `core:data`, one instance, started when logged in):
  1. If `lastSeq == 0` → snapshot: page through `GET /videos`, upsert, store `X-Sync-Seq` of the
     first page as `lastSeq` after all pages are stored (then catch up from it).
  2. Catch-up: loop `GET /sync/changes?after=lastSeq` until `hasMore == false`; on 410 → clear
     synced rows (keep pending) and snapshot again.
  3. Live: on WS connect send `HELLO {lastSeq}`; for each event: `seq <= lastSeq` → ignore
     (duplicate); `seq == lastSeq + 1` → apply + advance; gap → run catch-up.
  4. Apply rules: upsert only if `incoming.version >= local.version` and local row isn't pending
     (pending rows are reconciled by the outbox result); deletes remove the row.
  5. All of apply + `lastSeq` update in one Room transaction.
  6. Triggers: login, app foreground, WS reconnect, network regained, pull-to-refresh.

### 9.2 Phone UI (functional; polished in Phase 9)
- **AddVideoScreen / sheet**: URL field with "Paste" button (read the clipboard **only on tap** —
  Android 12+ shows a toast on clipboard access), title, category (dropdown of existing +
  "New category"), notes, Save. Validation errors inline.
- **Share target** `ShareReceiverActivity` (translucent, `excludeFromRecents`, `taskAffinity=""`):
  ```xml
  <intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/plain"/>
  </intent-filter>
  ```
  Extract URLs from `EXTRA_TEXT` with `https?://[^\s<>"]+`, trimming trailing `.,;:!?)]}'"`;
  title candidate = `EXTRA_SUBJECT` or the shared text minus the URL (WhatsApp/Telegram send
  message text with the link). One URL → quick-save bottom sheet (title prefilled, category
  chip, Save / Edit); several → pick list; none → "No link found in what you shared".
  If signed out → explain and open login; after login resume the save.
- **LibraryScreen (basic)**: list with title, domain, category chip, status badge; search bar;
  category filter chips; sort menu; swipe or menu to delete with **Undo** snackbar (delay the
  PENDING_DELETE commit by 5 s); tap → **VideoDetailScreen** with edit/delete.
- Sync indicator: small "Offline — changes will sync" banner when not connected and outbox not empty.

### 9.3 TV UI (functional; polished in Phase 10)
- **TvLibraryScreen**: rows "Recently added" and one row per category; cards with title,
  domain, status; D-pad focus; search entry with the TV keyboard; empty state "Share a video
  link from your phone to see it here".
- **TvVideoDetailScreen**: title, domain, notes, created date; buttons **Play** and **Download**
  (hidden until Phases 5/6 via a `FeatureSwitches` object), **Delete** (confirm dialog; focus
  defaults to Cancel).
- Live updates: new video from the phone appears and shows a brief toast "New: <title>".

### 9.4 Android tests
- `VideosRepositoryTest` (in-memory Room + MockWebServer): create offline → online flush; duplicate; limit; conflict server-wins; delete with undo.
- `SyncManagerTest`: snapshot, catch-up with pagination, duplicate seq ignored, gap triggers catch-up, 410 → resnapshot keeps pending rows.
- `ShareTextParserTest`: WhatsApp-style text, Telegram, multiple links, trailing punctuation, no link, `javascript:` ignored.
- Compose UI: Add video validation; TV library D-pad navigation to Delete and back.

---

## 10. File structure (new/changed)
```
backend/src/common/url/{normalize-url.ts,default-title.ts,*.spec.ts}
backend/src/common/pagination/cursor.ts
backend/src/modules/sync/{sync.module.ts,sync.service.ts,sync.controller.ts,sync-retention.cron.ts}
backend/src/modules/videos/{videos.module.ts,videos.controller.ts,videos.service.ts,video.mapper.ts,dto/*}
backend/test/{videos.e2e-spec.ts,sync.e2e-spec.ts}
android/core/database/…/{VideoEntity.kt,VideoDao.kt,VideoFts.kt,SyncStateDao.kt,migrations/*}
android/core/data/…/{VideosRepository.kt,VideoOutboxWorker.kt,SyncManager.kt,ShareTextParser.kt}
android/app-phone/…/{share/ShareReceiverActivity.kt,share/QuickSaveSheet.kt,library/*,video/*}
android/app-tv/…/{library/TvLibraryScreen.kt,library/TvLibraryViewModel.kt,detail/TvVideoDetailScreen.kt}
```

## 11. Security requirements
- `userId` from the token only; every query filtered by it; IDs from clients validated as UUIDs
  and checked for cross-user collisions (`ID_CONFLICT`).
- URL validation server-side (never trust the client); limit lengths of all text fields; strip
  control characters.
- Do not fetch the URL in this phase (no SSRF surface yet).
- Logs: only `normalizedUrl` origin + path, never query strings.
- Share receiver treats incoming text as untrusted; never auto-opens links.

## 12. Verification commands
```bash
make test
curl -s -XPOST localhost:3000/api/v1/videos -H "authorization: Bearer $AT" -H 'content-type: application/json' \
  -d '{"id":"0192cccc-0000-7000-8000-000000000001","sourceUrl":"https://test-videos.example/sample.mp4?utm_source=x","category":"Test"}' | jq
curl -s "localhost:3000/api/v1/videos?q=sample" -H "authorization: Bearer $AT" -i | grep -i x-sync-seq
curl -s "localhost:3000/api/v1/sync/changes?after=0" -H "authorization: Bearer $AT" | jq '.events[] | {seq,type}'
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Watch this https://test-videos.example/a.mp4" <phone-package>
```

## 13. Acceptance criteria
- [ ] Paste and Share (WhatsApp-style text) save links; multiple links → picker; no link → message.
- [ ] Title/category/notes editable; delete with undo; duplicates detected after normalization.
- [ ] Link saved on phone appears on TV in < 2 s while both are online.
- [ ] Offline phone saves/edits/deletes sync automatically when back online.
- [ ] TV offline then online catches up via `HELLO`/change feed with no duplicates or gaps.
- [ ] TV delete propagates to phone.
- [ ] Search, category filter and sort work on phone (local) and in the API.
- [ ] `max_saved_links` enforced by the backend with a clear message on the phone.
- [ ] Version conflicts handled (server wins) with a visible notice.
- [ ] Strict per-user isolation (tests).
- [ ] All tests pass; OpenAPI and architecture doc updated.

## 14. Manual test script
1. In WhatsApp (or `adb` share command) share a message containing a link → quick-save → TV shows it.
2. Airplane mode on phone → add 2 links, edit 1 → airplane off → TV updates.
3. Disconnect TV network → add/delete on phone → reconnect TV → library correct.
4. Edit the same video title on phone and TV at nearly the same time → one wins, the other shows "Updated on another device".
5. Save the same link twice (with and without `utm_source`) → "Already in your library".

## 15. Pitfalls
- `BigInt` columns: serialize as numbers only if < 2^53 (they will be); convert explicitly in mappers.
- Prisma interactive transactions have a default 5 s timeout — keep work inside them small.
- Don't publish realtime events inside the transaction (a rollback would leave ghost events).
- Android: `ShareReceiverActivity` may be launched while the main activity is in the background —
  don't assume the nav graph exists; keep it self-contained.
- Some apps share `text/plain` with only a title and the URL in `EXTRA_SUBJECT` — check both extras.
