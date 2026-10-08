# VideoBridge — Architecture, Contracts & Conventions

> **Read this file before every phase.** Every phase file assumes the names, formats and
> rules defined here. If a phase needs to deviate, it must say so explicitly and the
> deviation must be recorded as an ADR in `docs/decisions/`.

> **Deviation (2026-10-08): the database is MongoDB, not PostgreSQL.** See
> `docs/decisions/ADR-0006-mongodb.md` for how every PostgreSQL-specific rule in this pack maps
> to MongoDB.

---

## 1. What we are building (one paragraph)

VideoBridge lets a person collect video links on their **Android phone** (paste, or Android
Share from WhatsApp/Telegram/Chrome), and see the same library on their **Android TV**, where
they can **play** the video (Media3/ExoPlayer) or **download it directly from the source
server to TV internal storage, a USB stick or an external HDD**. A **cloud backend** (NestJS +
PostgreSQL + Redis) stores accounts, devices, links, metadata, playback positions, download
state, subscriptions and entitlements, and keeps every device in sync over **WebSocket**.
The backend **never stores or proxies video bytes**.

---

## 2. System diagram

```
 ┌──────────────────────┐        REST (HTTPS) + WebSocket (WSS)        ┌──────────────────────────┐
 │  Android Phone app   │ ───────────────────────────────────────────► │   Cloud backend (NestJS)  │
 │  Kotlin / Compose    │ ◄─────────────────────────────────────────── │   /api/v1/*   /ws         │
 │  Room = local truth  │                                              │                           │
 └──────────────────────┘                                              │  Auth · Devices · Pairing │
                                                                       │  Videos · Metadata · Sync │
 ┌──────────────────────┐        REST (HTTPS) + WebSocket (WSS)        │  Downloads (state only)   │
 │  Android TV app      │ ───────────────────────────────────────────► │  Entitlements · Billing   │
 │  Compose for TV      │ ◄─────────────────────────────────────────── │  Notifications · Admin    │
 │  Media3 · Downloads  │                                              └──────┬─────────────┬──────┘
 │  SAF storage         │                                                     │             │
 └─────────┬────────────┘                                              ┌──────▼─────┐ ┌─────▼─────┐
           │  video bytes (HTTP range requests)                        │ PostgreSQL │ │   Redis   │
           ▼                                                           └────────────┘ └───────────┘
 ┌──────────────────────┐        ┌───────────────────────────────┐
 │ Source video server  │        │ Internal storage / USB / HDD  │◄── TV writes via Storage Access Framework
 └──────────────────────┘        └───────────────────────────────┘
```

**Golden rule:** the only component that ever touches video bytes is the TV (and the phone's
player if phone playback is ever added). The backend may make *small* inspection requests
(HEAD / `Range: bytes=0-0` / first ≤64 KB of an HTML page) to classify a URL — never a full
download, never a proxy.

---

## 3. Technology choices (locked unless an ADR changes them)

| Area | Choice | Notes |
|---|---|---|
| Android language/UI | Kotlin, Jetpack Compose, Material 3 | Phone uses `androidx.compose.material3` |
| Android TV UI | Compose for TV (`androidx.tv:tv-material`) | Use standard `LazyRow`/`LazyColumn` (the `TvLazy*` APIs are deprecated) |
| DI | Hilt | `@HiltAndroidApp`, `@HiltViewModel`, `HiltWorkerFactory` |
| Async | Coroutines + Flow | No RxJava, no LiveData in new code |
| Local DB | Room (KSP) | Room is the **local source of truth** for UI |
| Prefs | DataStore (Proto or Preferences) | Tokens encrypted with Tink AEAD + Android Keystore |
| Background | WorkManager | Long-running downloads use `setForeground` with type `dataSync` |
| Networking | Retrofit + OkHttp + kotlinx.serialization | OkHttp also used for WebSocket and downloads |
| Playback | Media3 ExoPlayer (+ `-hls`, `-dash`, `-ui`) | No DRM circumvention, ever |
| Storage | Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) | Fallback: app-specific dirs from `getExternalFilesDirs()` (see Phase 6) |
| Images | Coil 3 | |
| QR | ZXing core (TV renders), Google code scanner `play-services-code-scanner` (phone scans, no camera permission) | Manual code entry always available |
| Backend | Node.js (current LTS) + TypeScript (strict) + NestJS | |
| ORM / migrations | Prisma | `prisma migrate` is the migration framework; raw SQL migrations allowed for indexes/constraints Prisma can't express |
| DB | PostgreSQL 16+ | |
| Cache / rate limit / pub-sub | Redis 7+ (ioredis) | |
| Validation | `class-validator` DTOs (requests), `zod` (env config) | |
| Logging | `nestjs-pino` with redaction | |
| API docs | `@nestjs/swagger` → OpenAPI 3 at `/api/docs`, JSON exported to `docs/api/openapi.json` | |
| WebSocket | `@nestjs/websockets` with the `ws` adapter (not socket.io) | Plain JSON envelope, works with OkHttp |
| Tests (backend) | Jest (unit), Jest + Supertest (e2e) against real Postgres/Redis from Docker | |
| Tests (Android) | JUnit4/5, Turbine, MockK, Robolectric where useful, Compose UI tests, MockWebServer | |
| Admin web (Phase 13) | React + Vite + TypeScript, TanStack Query | Talks only to `/api/v1/admin/*` |
| CI | GitHub Actions | |

**Versions:** always use the latest *stable* versions that are compatible with each other and
pin them in `android/gradle/libs.versions.toml` and `backend/package.json`. Never use alpha
libraries unless the phase file says so. Record the chosen versions in the phase report.

---

## 4. Repository layout (target end state)

```
videobridge/
├── CLAUDE.md                         # master instructions for Claude Code (copy from this pack)
├── README.md                         # human quick start
├── Makefile                          # make dev / make test / make lint / make android
├── .editorconfig  .gitignore  .gitattributes
├── .github/workflows/                # backend.yml, android.yml, admin.yml (P13), deploy.yml (P17)
├── docs/
│   ├── phases/                       # THIS PACK (phase-XX-*.md, 00-architecture-and-conventions.md)
│   ├── phase-reports/                # PHASE-XX-report.md written at the end of each phase
│   ├── decisions/                    # ADR-0001-*.md …
│   ├── architecture.md               # living architecture doc (kept current)
│   ├── api/openapi.json              # exported each phase that changes the API
│   ├── device-compatibility.md       # TV models tested, USB/HDD quirks (from Phase 6)
│   ├── security-audit.md             # Phase 15
│   ├── testing/                      # Phase 16
│   └── ops/                          # Phase 17 runbooks
├── infra/
│   ├── docker-compose.yml            # dev: postgres, redis (+ mailhog-like tools if needed)
│   ├── docker-compose.test.yml       # isolated test db/redis
│   └── prod/                         # Phase 17
├── backend/
│   ├── package.json  tsconfig.json  nest-cli.json  .env.example  Dockerfile
│   ├── prisma/schema.prisma  prisma/migrations/  prisma/seed.ts
│   ├── src/
│   │   ├── main.ts  app.module.ts
│   │   ├── config/                   # env.schema.ts (zod), config.module.ts
│   │   ├── common/                   # filters/, guards/, interceptors/, decorators/, pipes/, errors/, utils/
│   │   ├── infra/                    # prisma/, redis/, logger/, clock/, ids/
│   │   └── modules/
│   │       ├── health/  auth/  users/  devices/  pairing/  realtime/  sync/
│   │       ├── videos/  metadata/  playback/  downloads/  storage/
│   │       ├── entitlements/  subscriptions/  billing/
│   │       ├── notifications/  admin/  audit/  feature-flags/
│   └── test/                         # e2e specs + helpers (factories, auth helper)
├── android/
│   ├── settings.gradle.kts  build.gradle.kts  gradle.properties
│   ├── gradle/libs.versions.toml
│   ├── build-logic/convention/       # convention plugins: android-app, android-library, compose, hilt, room
│   ├── app-phone/                    # com.videobridge.app (phone)
│   ├── app-tv/                       # com.videobridge.app (TV form-factor build) — see ADR in Phase 1
│   ├── core/
│   │   ├── model/                    # pure Kotlin data models shared by everything
│   │   ├── common/                   # dispatchers, Result types, time, logging (Timber)
│   │   ├── network/                  # Retrofit services, OkHttp, auth interceptor/authenticator, DTOs
│   │   ├── database/                 # Room DB, entities, DAOs, migrations
│   │   ├── datastore/                # settings + encrypted token store
│   │   ├── data/                     # repositories (Room + network), sync orchestration
│   │   ├── realtime/                 # WebSocket client, event decoding, reconnect
│   │   ├── player/                   # Media3 wrappers, PlayerFactory, data sources
│   │   ├── designsystem/             # phone theme + components
│   │   ├── tv-designsystem/          # TV theme + focus components
│   │   └── testing/                  # fakes, test rules, fixtures
│   ├── feature/                      # optional per-feature modules if screens grow large
│   └── tv/
│       └── download/                 # TV download engine (Phase 6, 7, 12)
└── admin-web/                        # Phase 13
```

Package root: `com.videobridge`. Module packages: `com.videobridge.core.network`,
`com.videobridge.phone.*`, `com.videobridge.tv.*`, `com.videobridge.tv.download.*`.

---

## 5. Environments

| Env | `APP_ENV` | Backend URL (example) | Android flavor |
|---|---|---|---|
| Development | `development` | `http://10.0.2.2:3000` (emulator) or `http://<LAN-IP>:3000` (real TV/phone) | `dev` |
| Staging | `staging` | `https://api-staging.<domain>` | `staging` |
| Production | `production` | `https://api.<domain>` | `prod` |

- Android: flavor dimension `env` with `dev`, `staging`, `prod`. `BuildConfig.API_BASE_URL`
  and `BuildConfig.WS_URL` come from the flavor. Cleartext HTTP is allowed **only** in the `dev`
  flavor via `src/dev/res/xml/network_security_config.xml`.
- Backend: all configuration comes from environment variables validated by a zod schema at
  boot. The process **refuses to start** if a variable is missing or invalid. `.env.example`
  lists every variable with a safe placeholder. Real `.env` files are git-ignored.

---

## 6. Identifiers, time, money

- **IDs:** UUIDv7 (time-ordered) everywhere, generated by the backend **or** by the client for
  client-created entities (videos, download jobs) so that creates are idempotent and work
  offline. Postgres type `uuid`.
- **Time:** store `timestamptz` in UTC; JSON uses ISO-8601 with `Z`
  (`2026-10-07T16:26:52.123Z`). Durations are integer milliseconds with an `Ms` suffix
  (`durationMs`, `positionMs`). Sizes are integer bytes with a `Bytes` suffix.
- **Money:** integer minor units (paise) + ISO currency: `{ "amountMinor": 9900, "currency": "INR" }`.
- **Phone numbers:** E.164 (`+919876543210`), normalized with `libphonenumber-js`, default region `IN`.

---

## 7. REST API conventions

- Base path: `/api/v1`. JSON only. `camelCase` fields in JSON, `snake_case` in SQL.
- Auth header: `Authorization: Bearer <accessToken>`.
- Every request gets an `X-Request-Id` (generated if absent) that is echoed back and logged.
- Clients send `X-Device-Id` (the server-side device id once known) and `X-App-Version`,
  `X-Platform: android-phone | android-tv`.
- **Pagination:** cursor based. Request `?limit=50&cursor=<opaque>`; response
  `{ "items": [...], "nextCursor": "..." | null }`. Max limit 100.
- **Idempotency:** creates that use client IDs are idempotent on `(userId, id)`. Other unsafe
  POSTs accept an optional `Idempotency-Key` header (stored 24 h in Redis).
- **Optimistic concurrency:** mutable resources carry an integer `version`. Updates send
  `"version": n`; mismatches return `409 VERSION_CONFLICT` with the current resource.
- **Error envelope** (every non-2xx):

```json
{
  "error": {
    "code": "OTP_EXPIRED",
    "message": "The code has expired. Request a new one.",
    "details": { "retryAfterSeconds": 30 },
    "requestId": "01J9…"
  }
}
```

- `code` is a stable `UPPER_SNAKE_CASE` string that clients switch on. `message` is safe to
  show to users. Never leak stack traces, SQL, or internal hostnames.
- Standard codes (extend per phase, keep a single registry in `backend/src/common/errors/error-codes.ts`):
  `VALIDATION_FAILED`(400), `UNAUTHENTICATED`(401), `TOKEN_EXPIRED`(401), `FORBIDDEN`(403),
  `NOT_FOUND`(404), `VERSION_CONFLICT`(409), `DUPLICATE`(409), `RATE_LIMITED`(429, with
  `Retry-After`), `ENTITLEMENT_LIMIT`(403, with `details.entitlement`, `details.limit`,
  `details.current`), `INTERNAL`(500).

### API areas (owner phase in brackets)

```
/api/v1/health                 [P1]
/api/v1/auth/*                 [P2]
/api/v1/users/*                [P2]
/api/v1/devices/*              [P2 minimal, P3 full]
/api/v1/pairing/*              [P3]
/api/v1/videos/*               [P4]
/api/v1/sync/*                 [P4 basic, P11 full]
/api/v1/metadata/*             [P5]   (URL inspection)
/api/v1/playback/*             [P5]
/api/v1/downloads/*            [P6, P12]
/api/v1/storage/*              [P6]
/api/v1/entitlements/*         [P8]  (a stub exists from P3)
/api/v1/subscriptions/*        [P8]
/api/v1/billing/*              [P8]  (webhooks / purchase verification)
/api/v1/notifications/*        [P14]
/api/v1/admin/*                [P13]
/api/v1/config                 [P13]  (feature flags, min app version, maintenance banner)
/api/v1/users/me/export        [P15]  (data export)
/ws                            [P3 presence, P4 video events, P11 full]
```

---

## 8. Database conventions

- Table names: plural `snake_case`. Columns: `snake_case`. Prisma models: `PascalCase`
  singular with `@@map("table_name")` and `@map("column_name")`.
- Every table: `id uuid pk`, `created_at timestamptz not null default now()`,
  `updated_at timestamptz not null` (Prisma `@updatedAt`) unless it is an append-only log.
- User-owned rows always carry `user_id` and **every query filters by `user_id` from the
  authenticated principal** — never from the request body. Repository methods take `userId`
  as the first argument to make this impossible to forget.
- Soft delete (`deleted_at`) for syncable entities (videos, download jobs) so deletions can be
  synced. Hard delete is done later by a retention job.
- Enums: Postgres enums via Prisma `enum`.
- Every migration is forward-only and reviewed. Destructive changes are done in two phases
  (add → backfill → switch → drop later).
- Seed script (`prisma/seed.ts`) seeds plans/entitlements (from P8), feature flags (P13) and a
  dev test user (development only).

### Table catalogue (owner phase)

| Table | Phase | Purpose |
|---|---|---|
| `users` | P2 | account, phone, status, `sync_seq` (P4) |
| `otp_requests` | P2 | hashed OTPs, attempts, expiry |
| `devices` | P2 (min) / P3 | PHONE / ANDROID_TV devices |
| `sessions` | P2 | refresh-token sessions per device |
| `pairing_sessions` | P3 | TV pairing codes |
| `videos` | P4 | saved links |
| `sync_events` | P4 (min) / P11 | per-user ordered change log |
| `video_metadata` | P5 | inspection results |
| `playback_positions` | P5 | resume positions |
| `storage_locations` | P6 | TV storage destinations (labels only, no raw paths) |
| `download_jobs` | P6 | mirror of TV download jobs |
| `download_events` | P6 | job state transitions |
| `plans` `plan_prices` `plan_entitlements` | P8 | catalogue |
| `subscriptions` `subscription_events` | P8 | user subscriptions |
| `payments` `invoices` | P8 | payment records |
| `user_entitlements` | P8 | per-user overrides / grants |
| `download_schedules` | P12 | scheduled windows |
| `admin_users` `admin_sessions` | P13 | admin identities (separate from app users) |
| `audit_logs` | P13 | append-only admin/security audit |
| `feature_flags` | P13 | flags and system configuration |
| `notifications` `notification_preferences` | P14 | notification inbox + prefs |

---

## 9. Realtime (WebSocket) contract

- URL: `wss://<host>/ws` (dev: `ws://10.0.2.2:3000/ws`).
- Authentication: `Authorization: Bearer <accessToken>` header on the upgrade request
  (OkHttp supports this). Tokens are **never** put in the query string. Connection is closed
  with code `4401` when the token is invalid/expired, and `4403` when the device is revoked.
- Heartbeat: client sends `PING` every 25 s; server closes idle sockets after 60 s.
- Envelope (both directions):

```json
{
  "v": 1,
  "id": "0192f3c4-…",            // event id (UUIDv7) — used for de-duplication
  "type": "VIDEO_CREATED",
  "seq": 1042,                    // per-user sequence for persisted events; null for ephemeral
  "ts": "2026-10-07T16:26:52.123Z",
  "originDeviceId": "0192…",      // device that caused it (clients ignore their own echoes)
  "data": { }
}
```

- Client → server types: `HELLO {lastSeq, appVersion}`, `PING`, `ACK {seq}`,
  `DOWNLOAD_PROGRESS` (TV only, ephemeral), `PLAYBACK_UPDATED` (optional, can be REST).
- Server → client control types: `WELCOME {deviceId, serverSeq}`, `PONG`,
  `RESYNC_REQUIRED {reason}`, `ERROR {code, message}`.
- Event catalogue (persisted unless marked *ephemeral*):

| Event | Introduced | Data |
|---|---|---|
| `DEVICE_ONLINE` / `DEVICE_OFFLINE` | P3 | `{deviceId}` *ephemeral* |
| `DEVICE_CONNECTED` | P3 | `{device}` |
| `DEVICE_UPDATED` | P3 | `{device}` |
| `DEVICE_REMOVED` | P3 | `{deviceId}` |
| `VIDEO_CREATED` / `VIDEO_UPDATED` | P4 | `{video}` (full resource incl. `version`) |
| `VIDEO_DELETED` | P4 | `{videoId, version}` |
| `VIDEO_METADATA_UPDATED` | P5 | `{videoId, metadata}` |
| `PLAYBACK_UPDATED` | P5/P11 | `{videoId, positionMs, durationMs, deviceId}` |
| `DOWNLOAD_REQUESTED` | P11 | `{jobRequest}` phone → TV "download this on my TV" |
| `DOWNLOAD_STARTED` / `DOWNLOAD_COMPLETED` / `DOWNLOAD_FAILED` / `DOWNLOAD_STATE_CHANGED` | P6/P11 | `{job}` |
| `DOWNLOAD_PROGRESS` | P6/P11 | `{jobId, bytes, totalBytes, speedBps, etaMs}` *ephemeral* |
| `SUBSCRIPTION_CHANGED` | P8/P11 | `{subscription, entitlements}` |
| `ENTITLEMENTS_CHANGED` | P8 | `{entitlements}` |
| `NOTIFICATION_CREATED` | P14 | `{notification}` |

Phase 11 adds `DEVICE_COMMAND`, `DEVICE_COMMAND_RESULT`, `STORAGE_LOCATION_UPDATED/REMOVED`,
`CATEGORY_RENAMED` and versioned JSON schemas; from Phase 11 on, `docs/api/events/` is the
authoritative catalogue.

- Reconnect: on connect the client sends `HELLO {lastSeq}`; the server replays events with
  `seq > lastSeq` (or sends `RESYNC_REQUIRED` if the gap is too old) and then streams live.
  The REST equivalent is `GET /api/v1/sync/changes?after=<seq>&limit=500`.

---

## 10. Authentication model (summary — Phase 2 is authoritative)

- Phone + OTP → account. Each app install registers a **device**; each device has one
  active **session**.
- Access token: JWT, 15 min, claims `sub` (userId), `did` (deviceId), `sid` (sessionId),
  `typ: "access"`, `ver` (token version for mass revocation).
- Refresh token: 256-bit random opaque string, stored as SHA-256 hash, **rotated on every
  use**, reuse of an old token revokes the session (reuse detection). Lifetime: phone 30 days
  sliding, TV 180 days sliding (TVs are rarely re-logged-in).
- Android stores tokens only in an encrypted DataStore (Tink AEAD, key in Android Keystore).
  OkHttp `Authenticator` performs a single-flight refresh on 401 `TOKEN_EXPIRED`.
- TV primary login = **pairing** (Phase 3); OTP on TV is a fallback.

---

## 11. Entitlements (summary — Phase 8 is authoritative)

Keys (backend is the authority; clients cache and display, never decide):

```
max_devices  max_tv_devices  max_saved_links  max_active_downloads  max_queued_downloads
max_storage_destinations  scheduled_downloads  bandwidth_controls  family_profiles
priority_support  advanced_playback  download_history
```

Until Phase 8 exists, an `EntitlementsService` stub (created in Phase 3) returns the FREE
defaults from configuration so that limit checks are wired in from the start and only the
data source changes later.

---

## 12. Download states (TV)

```
QUEUED → STARTING → DOWNLOADING ⇄ PAUSED
              │          │  └──► RETRYING ──► DOWNLOADING
              │          ├──► COMPLETED
              │          ├──► FAILED  ──(user retry)──► QUEUED
              └──────────┴──► CANCELLED
```

Pause reasons: `USER`, `NETWORK_LOST`, `STORAGE_REMOVED`, `WAITING_FOR_WIFI`, `OUTSIDE_SCHEDULE`, `APP_RESTART`, `PREEMPTED` (P12).
Failure codes: `HTTP_4XX`, `HTTP_5XX`, `SOURCE_CHANGED`, `INSUFFICIENT_STORAGE`,
`STORAGE_PERMISSION_LOST`, `STORAGE_UNAVAILABLE`, `FILE_TOO_LARGE_FOR_FILESYSTEM`, `UNSUPPORTED_MEDIA`, `TOO_MANY_RETRIES`, `UNKNOWN`.

---

## 13. Logging & secrets

- Never log: OTPs, access/refresh tokens, pairing codes, poll tokens, `Authorization`
  headers, cookies, full phone numbers (log masked `+91******3210`), payment tokens, full
  video URLs with query strings (log `scheme://host/path` only; query strings often contain
  signed tokens).
- Pino redaction list lives in one place: `backend/src/infra/logger/redaction.ts`.
- Android: Timber; release builds plant no debug tree; never log tokens or URLs with queries.
- Secrets live only in environment variables / secret stores. Nothing secret in the Android
  app (no API keys that grant server access). Signing keys are never committed.

---

## 14. Coding standards

**Backend**
- TypeScript `strict: true`, no `any` in new code (use `unknown` + narrowing).
- One Nest module per domain. Controllers are thin; services hold logic; Prisma access is
  confined to services/repositories.
- DTOs with `class-validator` + Swagger decorators. Responses are explicit DTO mappers — never
  return Prisma models directly (prevents leaking columns like hashes).
- Use transactions for multi-row invariants (`prisma.$transaction`). Use
  `SELECT … FOR UPDATE` (via `$queryRaw`) where counting-then-inserting must be race-free
  (device limits, link limits).
- Use an injectable `Clock` so time-based logic (OTP expiry, grace periods) is testable.

**Android**
- MVVM + unidirectional data flow: `ViewModel` exposes `StateFlow<UiState>`; UI sends events.
- Repositories expose `Flow` from Room; network results are written into Room.
- No business logic in composables. No `GlobalScope`. Inject dispatchers.
- Every screen has Loading / Content / Empty / Error (and Offline where relevant) states.
- Strings in `strings.xml` (prepare for Hindi later). Content descriptions for icons.
- TV: every interactive element must have a visible focus state; test with D-pad only.

**Both**
- Small, reviewable commits per logical step: `feat(auth): verify otp endpoint`.
- Tests accompany code in the same step, not at the end.

---

## 15. Definition of done (every phase)

A phase is done only when all of these are true:

1. All tasks in the phase file are implemented, or explicitly deferred in the report with a reason.
2. Existing functionality still works (previous phases' tests pass).
3. `make lint` and `make test` pass; backend builds; `./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug` pass.
4. Migrations created and applied cleanly on an empty DB **and** on a DB at the previous phase.
5. OpenAPI regenerated into `docs/api/openapi.json` if the API changed.
6. `docs/architecture.md` updated if architecture changed; ADRs added for decisions.
7. No secrets committed (`git diff` reviewed; `.env` untracked).
8. `docs/phase-reports/PHASE-XX-report.md` written using `PHASE-REPORT-TEMPLATE.md`.
9. Claude **stops** and waits for the human before starting the next phase.
