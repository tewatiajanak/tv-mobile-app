# Phase 01 — Project Foundation — Completion Report

**Date:** 2026-10-08  **Branch / commits:** none — work is uncommitted in the working tree (the
owner asked to leave git for later).

**Status: code complete; three acceptance items are not verified** because this machine has no
working Docker and no Android emulator. See §7 and §10.

> **Addendum, same day — database changed to MongoDB Atlas** at the owner's request (ADR-0006).
> Sections below describe the original PostgreSQL build; this is what changed afterwards:
> - Prisma now uses `provider = "mongodb"`. The `0001_init` migration is gone: MongoDB has no
>   migration files, `prisma db push` syncs the schema. `schema_meta` is a collection.
> - `DATABASE_URL` must be `mongodb://` or `mongodb+srv://`; readiness pings MongoDB.
> - Compose files, Makefile, CI workflow, README, architecture doc and CLAUDE.md updated. Docker
>   is now optional (`make services`); `make dev` uses whatever `backend/.env` points at.
> - Re-verified against the owner's Atlas cluster (databases `videobridge` and
>   `videobridge_test`) with a temporary local Redis: lint ✔, typecheck ✔, build ✔,
>   **86 unit tests passed, 16 e2e passed**, `prisma db push` ✔ on both databases,
>   `/health/ready` 200. Android was not touched and not re-run.
> - Bugs found and fixed during the switch: env validation rejected multi-host MongoDB URIs; a
>   second `npm run build` emitted nothing (stale incremental build info); the OpenAPI export
>   exited silently on error and depended on the caller's `DATABASE_URL`; `.env.test` was never
>   actually loaded under Jest; the first readiness check after boot timed out (lazy connect).
> - Redis was then made optional and is not used (ADR-0007): 88 unit and 16 e2e tests pass
>   with no Redis; readiness reports `redis: "disabled"`.
> - **TV app verified on a real TV** (Xiaomi MiTV-AXSO2, Android 9): installed over adb, shows
>   "Backend: OK (development)", Retry has focus and the remote's OK key triggers a new check
>   (seen in the backend log). Not checked: the launcher row/banner.
> - **Phone app verified on a real phone** (OnePlus CPH2619, Android 16): shows
>   "Backend: OK (development)". The first adb install was refused
>   (`INSTALL_FAILED_VERIFICATION_FAILURE`) until the owner changed the phone's developer settings.
>   Not checked on either device: the error state with the backend stopped.
> - Still open: phone error/Retry path on a device on a device; Compose files and
>   CI with MongoDB are unexecuted; `mongodb+srv://` does not work through Prisma on this Mac
>   (its `/etc/resolv.conf` is unparseable), so `.env` uses the multi-host form.

## 1. Implementation summary
The monorepo now has a NestJS backend that validates its environment, connects to PostgreSQL and
Redis, and serves liveness and readiness endpoints with a uniform error envelope, request ids,
redacted logging and Swagger. A phone app and a TV app build as `devDebug` APKs; each calls the
health endpoint and shows "Backend: OK (development)" or a readable error with Retry. Lint, type
checks, unit tests and e2e tests pass locally, and two GitHub Actions workflows are written.
There are no product features and no product tables.

The backend was verified against a real PostgreSQL and Redis started as temporary local
processes (not Docker). The apps were built and unit-tested but **never installed or launched**.

## 2. Files created / modified
| Path | Change | Why |
|---|---|---|
| `.gitignore`, `.editorconfig`, `.gitattributes`, `Makefile`, `README.md` | created | repo scaffolding, commands, quick start |
| `infra/docker-compose.yml`, `infra/docker-compose.test.yml` | created | dev and disposable test services |
| `.github/workflows/backend.yml`, `android.yml` | created | CI |
| `backend/**` | created | config, infra (prisma/redis/logger/clock/ids), common (errors/filter/pipe/middleware), health module, tests, Dockerfile |
| `backend/prisma/**` | created | schema, `0001_init` migration, no-op seed |
| `android/build-logic/**`, `android/gradle/libs.versions.toml` | created | six convention plugins, version catalog |
| `android/core/**` (10 modules) | created | model, common, network, database, datastore, data, player, designsystem, tv-designsystem, testing |
| `android/app-phone/**`, `android/app-tv/**` | created | app shells with startup screens |
| `docs/architecture.md`, `docs/device-compatibility.md`, `docs/decisions/ADR-0001…0005`, `docs/api/openapi.json` | created | docs |
| `CLAUDE.md` | modified | status and commands |

## 3. Database changes
- Migration `prisma/migrations/0001_init`: enables `citext` and `pgcrypto`; creates `schema_meta`
  (`key text pk, value text, updated_at timestamptz`). No product tables.
- Backfills: none. Rollback: forward-only; nothing depends on it yet.

## 4. API changes
| Method | Path | Auth | Change |
|---|---|---|---|
| GET | `/api/v1/health` | public | new — liveness |
| GET | `/api/v1/health/ready` | public | new — 200, or 503 `NOT_READY` with `details.checks` |
- WebSocket events: none.
- `docs/api/openapi.json` regenerated: yes.
- Error codes added beyond the conventions list: `NOT_READY`, `BAD_REQUEST`, `CONFLICT`,
  `METHOD_NOT_ALLOWED`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `SERVICE_UNAVAILABLE`.

## 5. Android changes
- Phone: `VideoBridgePhoneApp`, `MainActivity`, `StartupScreen`/`StartupViewModel`, `NoopWorker`.
- TV: `VideoBridgeTvApp`, `TvMainActivity`, `TvStartupScreen` (Retry takes focus), `NoopWorker`,
  leanback launcher entry, placeholder banner.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`. Default `WorkManagerInitializer` removed.
- Room schema version: 1 (exported to `android/core/database/schemas/`).

## 6. Tests and build results
```
backend: npm run lint ✔  format:check ✔  typecheck ✔  build ✔
         npm test          → 82 passed, 0 failed (8 suites)
         npm run test:e2e  → 16 passed, 0 failed   (real PostgreSQL + Redis on 55432/56379)
         prisma migrate deploy ✔  migrate status "up to date" ✔  migrate diff "no difference" ✔
android: ./gradlew spotlessCheck detekt lintDevDebug testDevDebugUnitTest
                   :app-phone:assembleDevDebug :app-tv:assembleDevDebug → BUILD SUCCESSFUL
         unit tests → 31 passed, 0 failed (11 classes)
         lint       → 0 errors (app-phone 6 warnings, app-tv 5 warnings)
```
Not run: `make dev`, `make test`, `make lint` as written (they call `docker compose`), the Docker
image build, both CI workflows, and anything on an emulator. The temporary PostgreSQL was
version 18, not 16.

## 7. Acceptance criteria
- [ ] `make dev` starts Postgres, Redis and the backend — **not run: no Docker.** The backend itself boots cleanly against real services.
- [x] Backend refuses to boot with a missing/invalid `DATABASE_URL` and names the variable (exit 1, value not printed).
- [x] `/health` 200; `/health/ready` 200, and 503 when Redis is down (stopped Redis → 503 `redis: down` → restarted → 200). DB-down covered by e2e.
- [x] Every error uses the envelope with `requestId`; `X-Request-Id` echoed (e2e).
- [x] Logs are JSON outside a dev terminal and redact authorization headers (unit test + observed). Pretty dev output not observed.
- [x] Swagger UI at `/api/docs`; `docs/api/openapi.json` exported.
- [x] Initial migration applies to an empty DB; `prisma migrate status` clean.
- [ ] `make lint` and `make test` pass; CI green — **the underlying commands pass; the make targets and CI were not run.**
- [ ] Phone app launches and shows "Backend: OK (development)" — **not verified: no emulator.** Covered only by a Robolectric UI test with a fake repository.
- [ ] TV app in the launcher row, launches, Retry reachable by D-pad — **not verified: no emulator.** Manifest has the leanback entry and banner; a Robolectric test checks focus and the centre key.
- [x] Hilt, Room (schema exported), WorkManager (Hilt factory, tested), Media3 and Retrofit compile in the right modules.
- [x] No auth/subscription/download code or tables.
- [x] ADRs 0001–0005, architecture doc, device-compatibility doc and this report exist.

## 8. Manual testing checklist
Needs Docker and two emulators (§10).
1. `make dev`; open `http://localhost:3000/api/docs`.
2. Install the phone APK → "Backend: OK (development)". Stop the backend → Retry → friendly error. Start it → Retry → OK.
3. Install the TV APK → VideoBridge appears in the apps row with its banner → open → status OK → stop the backend → Retry with the D-pad → error shown, focus visible.
4. Real TV (optional): build with `-PVB_DEV_HOST=<LAN IP>`, `adb connect <tv-ip>`, install, confirm OK.

## 9. Security notes
- Config errors name variables, never values. Prisma/Redis connection errors are not logged (they can contain the connection string).
- Incoming `X-Request-Id` is accepted only if it matches `[A-Za-z0-9._-]{1,128}` (log-injection guard).
- Helmet on; CORS closed unless `CORS_ORIGINS` is set; 100 KB body limit; unknown properties rejected; validation errors never echo values.
- The Swagger-enabled CSP allows inline script/style. Swagger is off by default in production; revisit in Phase 15 if it is ever enabled there.
- Android: cleartext only in the `dev` flavor; `allowBackup=false`; the HTTP logger runs only in `dev`, redacts auth headers and strips query strings.
- `/health` exposes the version/git SHA publicly — review in Phase 15.

## 10. Known issues / deferred items
| Issue | Impact | Planned phase |
|---|---|---|
| Docker not usable on this Mac (Docker Desktop removed; `/usr/local/bin/docker*` are dangling links; Colima present but no docker CLI/QEMU) | `make dev/test/lint` and the image build can't run here | fix before Phase 2 |
| No emulator or system images; no device attached | apps never launched | fix before Phase 2 |
| 5.9 GB free disk, 8 GB RAM | emulator images + Docker will be tight; the full Android check took ~15 min | — |
| CI workflows and Dockerfile unexecuted | may need fixes on first push | first push |
| `GET /api/v1/health/ready` e2e test fails without the test services | expected; `make test-backend` starts them | — |
| Path alias `@/` is configured for types and Jest but source uses relative imports (plain `tsc` output doesn't rewrite aliases) | none | revisit if wanted |
| `AppError(code, message?, details?, httpStatus?)` — argument order differs from the phase file; status comes from the registry | later phase files assume `(code, httpStatus, message, details)` | note for Phase 2 |
| Kotlin target is 17 without a pinned toolchain (only JDK 21 installed) | none; CI uses JDK 17 | — |
| No Windows scripts (the machine is macOS); README says to use WSL | none | — |
| Placeholder icon and TV banner (no app name on the banner) | cosmetic | 18 |

## 11. Library versions chosen / changed
**Backend:** Node 22.18, NestJS 11.2.7, Prisma 6.19.3, ioredis 6.0.0, zod 4.6.5, nestjs-pino 5.3.1,
pino 10.4, helmet 8.3, uuid 11.1.1, TypeScript 5.9.3, Jest 30.5, ts-jest 29.4, ESLint 10.12
(flat config), typescript-eslint 8.71, Prettier 3.9. NestJS 12, Prisma 7, uuid 14 and
TypeScript 6 are newer but were not used — reasons in ADR-0002.

**Android:** Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1,
Compose BOM 2026.09.00, tv-material 1.1.0, Room 2.8.5, DataStore 1.2.1, WorkManager 2.12.0,
Media3 1.11.1, OkHttp 5.5.0, Retrofit 3.0.0, kotlinx.serialization 1.11.0, coroutines 1.11.0,
Coil 3.6.3 (catalog only), Robolectric 4.17, Turbine 1.2.1, Spotless 8.10.3 + ktlint 1.8.0,
detekt 1.23.8. `compileSdk 37` (required by the current Compose libraries; platform 37.0 was
installed on this machine), `targetSdk 36`, `minSdk 26`.

## 12. Next-phase recommendations
- Get Docker and both emulators working first and run §8; Phase 2's flows can't be checked without them.
- Jest must keep `watchman: false` on this machine (the installed Watchman is broken).
- Robolectric needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` (already in the convention plugins).
- Compose UI tests run on the JVM with Robolectric, not as instrumented tests; add instrumented ones once an emulator exists.
- Phase 2 adds Tink, `@nestjs/jwt`, `@nestjs/schedule`, `libphonenumber-js`.
