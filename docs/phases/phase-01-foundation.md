# Phase 1 — Project Foundation

> **Implement this phase only. Do not start Phase 2. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md and docs/phases/phase-01-foundation.md.
We are implementing Phase 1 (Project Foundation) only.
First inspect the environment: OS, Node/npm versions, Docker, Java/JDK, Android SDK location
(ANDROID_HOME / local.properties), available emulators (adb devices, emulator -list-avds).
Then give me a numbered implementation plan covering: repo scaffolding, backend, infra,
Android multi-module setup, CI, Makefile, docs. List the exact library versions you intend to
pin and any environment problems you found. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement your Phase 1 plan step by step. After each step: build, run its tests, fix
failures, and commit with a conventional commit message. Use the file structure in the phase
file. Do not implement authentication, devices, videos, subscriptions or downloads.
If a step fails for an environment reason you cannot fix (e.g. no Android SDK), stop and tell me
exactly what I need to install.
```

**Prompt C — verify & report**
```
Run every command in the "Verification commands" section of phase-01-foundation.md and show me
the results. Tick every acceptance criterion with evidence. Then write
docs/phase-reports/PHASE-01-report.md using docs/phases/PHASE-REPORT-TEMPLATE.md, update the
"Current status" and "Commands" sections of CLAUDE.md, and stop.
```

**Prompt D — fix (use as needed)**
```
<describe what failed / paste the error>. Diagnose the root cause before changing anything,
explain it in two or three sentences, then fix it with the smallest change, re-run the failing
command and the full test suite, and commit.
```

---

## 1. Goal

A clean, buildable monorepo where:

- `make dev` starts PostgreSQL + Redis in Docker and the NestJS backend in watch mode.
- `GET /api/v1/health/ready` proves the backend can reach PostgreSQL and Redis.
- The **phone app** and the **TV app** both build, install, launch and display
  "Backend: OK (development)" by calling the health endpoint.
- Lint, type-check, unit tests and e2e tests run locally and in GitHub Actions.
- Configuration for development / staging / production exists and is validated.

No product features yet. This phase is about making every later phase cheap and safe.

## 2. Prerequisites

- Empty git repo containing `CLAUDE.md` and `docs/phases/` (this pack).
- Docker, Node LTS, JDK 17+, Android SDK, an Android phone emulator and an Android TV emulator.

## 3. Scope

**In scope:** repo scaffolding, backend skeleton, Prisma + first migration, Redis client,
config validation, logging, error envelope, request IDs, health checks, OpenAPI, Docker
Compose, Android Gradle multi-module setup with convention plugins, phone + TV app shells,
Hilt/Room/WorkManager/Media3/Retrofit wiring, flavors, CI, Makefile, docs skeleton, ADRs.

**Out of scope:** authentication, users, devices, pairing, videos, subscriptions, downloads,
real UI design. Do not create tables for them.

---

## 4. Architecture decisions to record (ADRs)

Create `docs/decisions/` with short ADRs (Context / Decision / Consequences):

| ADR | Decision |
|---|---|
| `ADR-0001-monorepo.md` | Single repo: `backend/`, `android/`, `admin-web/` (later), `infra/`, `docs/`. |
| `ADR-0002-backend-stack.md` | NestJS + Prisma + PostgreSQL + Redis, `ws` WebSocket adapter, REST + OpenAPI. |
| `ADR-0003-android-packaging.md` | One `applicationId` `com.videobridge.app` for both phone and TV builds, published as one Play listing with an Android TV form-factor track. Debug builds use `applicationIdSuffix ".dev"`. Every uploaded AAB in one listing needs a unique `versionCode`, so use `versionCode = base * 10 + 1` for phone and `base * 10 + 2` for TV, where `base` comes from `version.properties`. Alternative (two package names, two listings) noted with trade-offs. |
| `ADR-0004-ids-and-time.md` | UUIDv7 ids, client-generated where offline creates are needed; UTC `timestamptz`. |
| `ADR-0005-no-video-proxy.md` | Backend never stores/proxies video bytes; only bounded inspection requests. |

---

## 5. Backend tasks

### 5.1 Scaffold
1. `backend/` with NestJS CLI layout, TypeScript `strict`, path alias `@/` → `src/`.
2. Scripts in `package.json`:
   `start:dev`, `build`, `start:prod`, `lint`, `format`, `format:check`, `typecheck`,
   `test`, `test:e2e`, `prisma:migrate`, `prisma:generate`, `prisma:seed`, `openapi:export`.
3. ESLint (typescript-eslint, recommended-type-checked) + Prettier. `no-floating-promises`,
   `no-explicit-any` as errors.

### 5.2 Configuration (`src/config/`)
- `env.schema.ts` — zod schema:

| Variable | Rule | Example |
|---|---|---|
| `NODE_ENV` | `development \| test \| production` | `development` |
| `APP_ENV` | `development \| staging \| production` | `development` |
| `PORT` | int, default 3000 | `3000` |
| `DATABASE_URL` | postgres URL | `postgresql://vb:vb@localhost:5432/videobridge` |
| `REDIS_URL` | redis URL | `redis://localhost:6379/0` |
| `LOG_LEVEL` | `fatal..trace` default `info` | `debug` |
| `CORS_ORIGINS` | comma list, default empty | `http://localhost:5173` |
| `SWAGGER_ENABLED` | bool, default `true` unless `APP_ENV=production` | `true` |
| `TRUST_PROXY` | bool, default false | `false` |

- `ConfigModule` exposes a typed `AppConfig` provider. Boot fails with a readable list of
  every invalid variable (no secret values printed).
- `.env.example` with every variable and safe placeholders. `.env` git-ignored.

### 5.3 Infrastructure modules (`src/infra/`)
- `prisma/prisma.service.ts` — extends `PrismaClient`, connects on init, graceful shutdown.
- `redis/redis.module.ts` — ioredis client provider `REDIS` (+ a separate subscriber
  connection factory for later pub/sub). Graceful quit.
- `logger/` — `nestjs-pino`; JSON logs in staging/prod, pretty in dev; `redaction.ts` with
  paths: `req.headers.authorization`, `req.headers.cookie`, `*.password`, `*.otp`, `*.code`,
  `*.token`, `*.accessToken`, `*.refreshToken`, `*.pollToken`, `*.secret`. Custom serializer
  logs request URL **without query string**.
- `clock/clock.ts` — `Clock` interface `{ now(): Date }` + `SystemClock`; tests use `FakeClock`.
- `ids/ids.ts` — `newId()` returning UUIDv7 (use the `uuid` package v7).

### 5.4 Common (`src/common/`)
- `errors/app-error.ts` — `AppError(code, httpStatus, message, details?)`.
- `errors/error-codes.ts` — registry of codes from the conventions doc.
- `filters/all-exceptions.filter.ts` — maps `AppError`, Nest `HttpException`,
  `ValidationError`, Prisma known errors (`P2002` → `DUPLICATE`, `P2025` → `NOT_FOUND`) and
  unknown errors (→ `INTERNAL`, logged with stack, message generic) into the error envelope.
- `middleware/request-id.middleware.ts` — reads/creates `X-Request-Id`, binds it to the pino
  logger context, echoes it in the response.
- `pipes/validation` — global `ValidationPipe({ whitelist: true, forbidNonWhitelisted: true, transform: true })`,
  errors converted to `VALIDATION_FAILED` with `details.fields`.
- `main.ts` — global prefix `api/v1` (exclude nothing; WebSocket path later `/ws`),
  `helmet()`, CORS from config, `app.enableShutdownHooks()`, body size limit 100 KB,
  Swagger at `/api/docs` when enabled, `trust proxy` from config.

### 5.5 Health module
- `GET /api/v1/health` → `200 { "status": "ok", "env": "development", "version": "<git sha or package version>", "time": "..." }` (liveness, no dependencies).
- `GET /api/v1/health/ready` → checks `SELECT 1` and Redis `PING` with 1 s timeouts →
  `200 { status: "ok", checks: { db: "up", redis: "up" } }` or `503` with the envelope code
  `NOT_READY` and which check failed. Use `@nestjs/terminus` or a small custom service.

### 5.6 Prisma
- `prisma/schema.prisma` with `datasource db` (postgresql) and `generator client`.
- First migration `0001_init`: enable extensions `citext` and `pgcrypto`. No product tables.
  (If Prisma refuses an empty schema, add a tiny `schema_meta` table: `key text pk, value text, updated_at timestamptz`.)
- `prisma/seed.ts` exists and is a no-op that logs "nothing to seed yet".

### 5.7 OpenAPI export
- `src/openapi.ts` script that boots the app in a "docs only" mode (no DB connect) and writes
  `docs/api/openapi.json`. `make openapi` runs it.

### 5.8 Tests
- Unit: config schema (valid/invalid), exception filter mapping, request-id middleware.
- E2E (`test/health.e2e-spec.ts`): `/health` 200; `/health/ready` 200 with real DB/Redis;
  unknown route → 404 envelope with `requestId`; validation error envelope (add a test-only
  controller registered only when `NODE_ENV=test`, or test the filter directly).
- `test/jest-e2e.json` uses `DATABASE_URL`/`REDIS_URL` from `.env.test` pointing at
  `infra/docker-compose.test.yml` services (ports 55432 / 56379) so dev data is never touched.

---

## 6. Infra tasks

`infra/docker-compose.yml`
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment: { POSTGRES_USER: vb, POSTGRES_PASSWORD: vb, POSTGRES_DB: videobridge }
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U vb"], interval: 5s, retries: 10 }
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck: { test: ["CMD", "redis-cli", "ping"], interval: 5s, retries: 10 }
volumes: { pgdata: {} }
```
`infra/docker-compose.test.yml` — same images, ports 55432/56379, `tmpfs` data (fast, disposable).

`backend/Dockerfile` — multi-stage (deps → build → runtime on `node:<lts>-alpine`), non-root
user, `prisma generate` in build stage, `HEALTHCHECK` hitting `/api/v1/health`. (Production
hardening comes in Phase 17; this just has to build and run.)

`Makefile` (root):
```
dev:          docker compose -f infra/docker-compose.yml up -d && cd backend && npm run start:dev
down:         docker compose -f infra/docker-compose.yml down
migrate:      cd backend && npx prisma migrate dev
test:         test-backend test-android
test-backend: docker compose -f infra/docker-compose.test.yml up -d && cd backend && npm test && npm run test:e2e
test-android: cd android && ./gradlew testDevDebugUnitTest
lint:         cd backend && npm run lint && npm run format:check && npm run typecheck; cd android && ./gradlew spotlessCheck detekt lintDevDebug
android:      cd android && ./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug
openapi:      cd backend && npm run openapi:export
```
(Provide a `scripts/*.ps1` equivalent or document WSL if the developer is on Windows — check
the OS during the inspection step.)

---

## 7. Android tasks

### 7.1 Gradle setup (`android/`)
- Gradle wrapper (latest stable), Kotlin (latest stable K2), AGP (latest stable),
  `gradle/libs.versions.toml` with every dependency and plugin.
- `build-logic/convention` included build with plugins:
  - `videobridge.android.application` — compileSdk/targetSdk (current Play requirement,
    36 at time of writing — verify), minSdk **26**, Java/Kotlin toolchain 17, flavors
    `env: dev | staging | prod`, buildConfig on.
  - `videobridge.android.library`
  - `videobridge.android.compose` — Compose compiler plugin + BOM.
  - `videobridge.android.hilt` — Hilt + KSP.
  - `videobridge.android.room` — Room + KSP + schema export to `$projectDir/schemas`.
  - `videobridge.jvm.library` — pure Kotlin modules (`core:model`).
- Spotless (ktlint) + detekt with a baseline config. Android lint `warningsAsErrors=false`,
  `abortOnError=true`.

### 7.2 Flavors & build config
| Flavor | `API_BASE_URL` | `WS_URL` | Cleartext |
|---|---|---|---|
| dev | `http://10.0.2.2:3000/` (override with `VB_DEV_HOST` Gradle property, e.g. `-PVB_DEV_HOST=192.168.1.20`) | `ws://…/ws` | allowed (dev network security config only) |
| staging | `https://api-staging.example.com/` | `wss://…/ws` | no |
| prod | `https://api.example.com/` | `wss://…/ws` | no |

`applicationIdSuffix`: dev `.dev`, staging `.staging`, prod none.

### 7.3 Modules to create now
| Module | Contents in Phase 1 |
|---|---|
| `core:model` | `HealthStatus` data class. |
| `core:common` | `DispatchersModule` (`@IoDispatcher`, `@DefaultDispatcher`), `AppResult<T>` sealed type, Timber init helper. |
| `core:network` | OkHttp client (timeouts 15 s connect / 30 s read, `HttpLoggingInterceptor` at BASIC in dev only and **headers redacted**), Retrofit with kotlinx.serialization converter, `HealthApi` (`GET api/v1/health`), `ApiErrorParser` that decodes the error envelope into `ApiException(code, message, httpStatus, details)`, headers interceptor adding `X-App-Version`, `X-Platform`, `X-Request-Id`. |
| `core:database` | `VideoBridgeDatabase` (Room, version 1) with one entity `KeyValueEntity(key, value, updatedAt)` + `KeyValueDao`. Exported schema committed. |
| `core:datastore` | `AppSettingsDataStore` (Preferences DataStore) — empty settings scaffold. |
| `core:data` | `HealthRepository` (calls `HealthApi`, maps to `AppResult<HealthStatus>`). |
| `core:player` | Media3 dependencies (`exoplayer`, `exoplayer-hls`, `exoplayer-dash`, `ui`), `PlayerFactory` interface + `DefaultPlayerFactory` building an `ExoPlayer` (not used yet). |
| `core:designsystem` | Phone `VideoBridgeTheme` (Material 3, dynamic color off by default, brand palette placeholder), typography. |
| `core:tv-designsystem` | TV `VideoBridgeTvTheme` using `androidx.tv.material3`. |
| `core:testing` | `MainDispatcherRule`, fake `HealthApi`. |
| `app-phone` | `@HiltAndroidApp VideoBridgePhoneApp`, `MainActivity` (single-activity, Compose, edge-to-edge), `StartupScreen` showing app version, flavor and "Backend: OK (development)" / error with Retry. |
| `app-tv` | `@HiltAndroidApp VideoBridgeTvApp`, `TvMainActivity`, `TvStartupScreen` with the same health check, a focusable "Retry" button. |

### 7.4 Manifests
- Phone: `INTERNET`, `ACCESS_NETWORK_STATE`. `android:networkSecurityConfig` per flavor.
- TV:
  ```xml
  <uses-feature android:name="android.software.leanback" android:required="true"/>
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <application android:banner="@drawable/tv_banner" ...>
    <activity android:name=".TvMainActivity" android:exported="true"
              android:screenOrientation="landscape">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      </intent-filter>
    </activity>
  ```
  Placeholder 320×180 banner (`tv_banner`), placeholder launcher icon.
- **WorkManager + Hilt:** both apps implement `Configuration.Provider` with
  `HiltWorkerFactory` and remove the default `WorkManagerInitializer` via the
  `androidx.startup` provider `tools:node="remove"` meta-data entry. Add a trivial
  `NoopWorker` + unit test proving the factory wiring.

### 7.5 Android tests
- `HealthRepositoryTest` with MockWebServer: 200 → success; 503 envelope → `ApiException(NOT_READY)`; timeout → network error.
- `ApiErrorParserTest`: valid envelope, malformed body, empty body.
- `StartupViewModelTest` (phone) with Turbine: Loading → Success / Error → Retry.
- One Compose UI test per app: the startup screen shows the status text (fake repository).

---

## 8. CI (`.github/workflows/`)

- `backend.yml` — on PR/push touching `backend/**` or `infra/**`: Node LTS, `npm ci`,
  lint, format check, typecheck, unit tests, then service containers Postgres 16 + Redis 7,
  `prisma migrate deploy`, e2e tests, `docker build` of backend (no push).
- `android.yml` — on PR/push touching `android/**`: JDK 17, Gradle cache,
  `spotlessCheck detekt lintDevDebug testDevDebugUnitTest :app-phone:assembleDevDebug :app-tv:assembleDevDebug`.
- Both upload test reports as artifacts on failure.

---

## 9. Docs

- Root `README.md`: prerequisites, quick start (`make dev`, run apps), how to point a real
  TV/phone at the dev backend (`-PVB_DEV_HOST=<LAN IP>`, firewall note).
- `docs/architecture.md`: the diagram and module map from the conventions doc, current state
  ("Phase 1: skeleton only").
- `docs/device-compatibility.md`: empty table (Model / Android version / USB / HDD / SAF picker present / notes).
- `docs/phase-reports/` folder.

---

## 10. File structure created in this phase

```
CLAUDE.md (updated)  README.md  Makefile  .editorconfig  .gitignore  .gitattributes
.github/workflows/backend.yml  .github/workflows/android.yml
docs/architecture.md  docs/device-compatibility.md  docs/decisions/ADR-0001..0005-*.md
docs/api/openapi.json  docs/phase-reports/PHASE-01-report.md
infra/docker-compose.yml  infra/docker-compose.test.yml
backend/{package.json,tsconfig.json,tsconfig.build.json,nest-cli.json,.eslintrc.*,.prettierrc,.env.example,Dockerfile,.dockerignore}
backend/prisma/{schema.prisma,seed.ts,migrations/…_init/}
backend/src/{main.ts,app.module.ts,openapi.ts}
backend/src/config/{env.schema.ts,config.module.ts,app-config.ts}
backend/src/infra/{prisma,redis,logger,clock,ids}/…
backend/src/common/{errors,filters,middleware,pipes}/…
backend/src/modules/health/{health.module.ts,health.controller.ts,health.service.ts}
backend/test/{jest-e2e.json,health.e2e-spec.ts,setup-e2e.ts}
android/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradle/libs.versions.toml}
android/build-logic/convention/…
android/core/{model,common,network,database,datastore,data,player,designsystem,tv-designsystem,testing}/…
android/app-phone/…  android/app-tv/…
```

---

## 11. Security requirements (this phase)

- `.gitignore` covers: `.env*` (except `.env.example`), `*.jks`, `*.keystore`,
  `local.properties`, `google-services.json`, `service-account*.json`, `node_modules`, build dirs.
- Logger redaction in place and unit-tested (log a fake `authorization` header and assert it
  is `[Redacted]`).
- `helmet` enabled; CORS closed by default; body size limited.
- Cleartext traffic only in the Android `dev` flavor.
- Swagger disabled by default in production.

---

## 12. Verification commands

```bash
make dev &                                 # or run in a second terminal
curl -s localhost:3000/api/v1/health | jq
curl -s localhost:3000/api/v1/health/ready | jq
docker compose -f infra/docker-compose.yml stop redis && curl -si localhost:3000/api/v1/health/ready | head -1   # expect 503
docker compose -f infra/docker-compose.yml start redis
make lint
make test
make openapi && git diff --stat docs/api/openapi.json
cd android && ./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug
adb -s <phone-emulator> install -r app-phone/build/outputs/apk/dev/debug/*.apk
adb -s <tv-emulator> install -r app-tv/build/outputs/apk/dev/debug/*.apk
```

## 13. Acceptance criteria

- [ ] `make dev` starts Postgres, Redis and the backend with no errors.
- [ ] Backend refuses to boot with a missing/invalid `DATABASE_URL` and prints which variable is wrong.
- [ ] `/api/v1/health` returns 200; `/api/v1/health/ready` returns 200 and 503 when Redis or DB is down.
- [ ] Every error response uses the envelope and includes `requestId`; `X-Request-Id` echoed.
- [ ] Logs are JSON in non-dev, pretty in dev, and redact authorization headers.
- [ ] Swagger UI loads at `/api/docs` in development; `docs/api/openapi.json` exported.
- [ ] Prisma initial migration applies to an empty DB; `prisma migrate status` is clean.
- [ ] `make lint` and `make test` pass locally; both CI workflows are green (or ready to be green once pushed).
- [ ] Phone app launches on the emulator and shows "Backend: OK (development)".
- [ ] TV app appears in the Android TV launcher row (banner shown), launches, shows the status, and Retry is reachable with the D-pad.
- [ ] Hilt, Room (schema exported), WorkManager (Hilt factory), Media3 and Retrofit compile in the right modules.
- [ ] No auth/subscription/download code or tables exist.
- [ ] ADRs 0001–0005, architecture doc, device-compatibility doc and PHASE-01 report exist.

## 14. Manual test script

1. `make dev`; open `http://localhost:3000/api/docs`.
2. Launch the phone emulator app → status OK. Stop the backend → tap Retry → friendly error. Start backend → Retry → OK.
3. Launch the TV emulator → find VideoBridge in the apps row → open → status OK → stop backend → press D-pad to Retry → error shown with focus visible.
4. (Optional) Real TV on the same Wi-Fi: build with `-PVB_DEV_HOST=<your LAN IP>`, install via `adb connect <tv-ip>`, confirm status OK.

## 15. Pitfalls

- Emulator → host is `10.0.2.2`; a real device needs your LAN IP and the backend bound to
  `0.0.0.0` (Nest listens on all interfaces when you pass `'0.0.0.0'` to `listen`).
- Android TV emulator images without Google Play are fine for this phase.
- Removing the default WorkManager initializer incorrectly causes a crash at first
  `WorkManager.getInstance` — cover it with the `NoopWorker` test.
- Keep Room `exportSchema = true`; later phases need migration tests against these JSON schemas.
- Don't let Prisma's client generation run against production URLs from a dev machine.
