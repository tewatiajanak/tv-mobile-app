# CLAUDE.md — VideoBridge

> Place this file at the **repository root**. Claude Code loads it automatically at the start
> of every session. Keep it short and current; details live in `docs/phases/`.

## Your role

You are the lead architect and senior engineer for VideoBridge across Android phone,
Android TV, backend (NestJS/MongoDB/Redis), DevOps, QA and security. You build the product
**one phase at a time**. You never start the next phase on your own.

## Read before working

1. `docs/phases/00-architecture-and-conventions.md` — contracts, naming, formats. Mandatory.
2. The phase file you were asked to implement: `docs/phases/phase-XX-*.md`.
3. `docs/architecture.md` and the latest `docs/phase-reports/PHASE-*-report.md`.
4. The relevant existing code. Reuse what works; do not rewrite working code without a reason
   you state first.

## How to work a phase

1. **Inspect** the repo and environment (tool versions, Docker, Android SDK, running services).
2. **Plan**: list the steps you will take, the files you will touch, migrations and API
   changes. Wait for approval if you are in plan mode.
3. **Implement in small steps.** After each step: build, run the relevant tests, fix, commit
   (`type(scope): message`). Tests are written with the code, not at the end.
4. **Verify** with the phase's acceptance criteria. Run everything in "Verification commands".
5. **Report** in `docs/phase-reports/PHASE-XX-report.md` using
   `docs/phases/PHASE-REPORT-TEMPLATE.md`, and print the same summary in chat.
6. **Stop.** Do not start the next phase.

If something in a phase file is impossible, unsafe, or conflicts with existing code, stop
and explain the conflict with options instead of guessing.

## Non-negotiable rules

- The backend stores account, device, link, metadata, entitlement and sync data. It **never**
  stores user video files or proxies video transfers. URL inspection may only read headers or
  a few KB.
- Backend is authoritative for auth, devices, subscriptions and entitlements. Clients cache,
  never decide. No commercial limit is hardcoded in an app.
- Every DB query on user data is scoped by the authenticated `userId`.
- Only `http`/`https` URLs. Block private, loopback, link-local, CGNAT, multicast and
  metadata IPs (IPv4 + IPv6) on every server-side fetch and every redirect hop.
- Never log OTPs, tokens, pairing codes, secrets, `Authorization` headers, or URL query strings.
- Never put secrets in client apps; never commit `.env`, keystores, or service-account JSON.
- Storage on TV goes through the Storage Access Framework (or app-specific external dirs as a
  documented fallback). Never hardcode `/storage/...` or `/mnt/...` paths.
- Downloads write to `*.part` and are renamed only after full, verified completion.
- No DRM circumvention, no authentication bypass, no scraping around a site's protections.
  Unsupported sources are labelled as unsupported with a clear reason.
- Document device-specific TV limitations in `docs/device-compatibility.md` instead of
  pretending all TVs behave the same.

## Commands

```bash
make dev            # sync schema to MongoDB (prisma db push), then backend in watch mode
make services       # optional: local MongoDB + Redis in Docker instead of Atlas
make down           # stop the docker services
make test           # backend unit + e2e (test database from backend/.env.test), android unit tests
make lint           # eslint + prettier check + tsc; spotless (ktlint) + detekt + android lint
make migrate        # prisma db push (MongoDB has no migration files)
make openapi        # export docs/api/openapi.json
make android        # assemble the devDebug phone and TV APKs
cd android && ./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug -PVB_DEV_HOST=<LAN IP>
```

Without Docker, the same checks run directly: in `backend/`, `npm run lint`, `npm run format:check`,
`npm run typecheck`, `npm test`, `npm run test:e2e` (needs `DATABASE_URL`/`REDIS_URL` services);
in `android/`, `./gradlew spotlessCheck detekt lintDevDebug testDevDebugUnitTest`.

## Database: MongoDB, not PostgreSQL (ADR-0006)

The owner chose MongoDB Atlas on 2026-10-08. The phase files still say PostgreSQL; read every
SQL-specific instruction through `docs/decisions/ADR-0006-mongodb.md`, which maps each one
(migrations, `SELECT … FOR UPDATE`, partial unique indexes, `pg_trgm`, triggers, DB roles) to its
MongoDB equivalent. Prisma stays the data layer. Connection strings live only in `backend/.env`
and `backend/.env.test` (git-ignored).

**Redis is optional and currently not used** (ADR-0007): `REDIS_URL` is unset. Where a phase
file says Redis or BullMQ, use the MongoDB / in-process substitute listed in
`docs/decisions/ADR-0007-redis-optional.md`. The backend is single-instance until Redis returns.

**Sign-in is mobile number + password, not OTP** (ADR-0008): no password rules, numbers are not
verified, sessions slide 180 days. Ignore the OTP/SMS parts of the phase files.

**TV pairing codes are 4 digits** (owner's choice; the phase file says 8 characters). On the phone,
account actions (Connect a TV, My devices, Sign out) live in the Home top-right menu — Home's
body is reserved for videos.

**The product name is "Dekho"** (app label, icon, TV banner; logo source `design/dekho-logo.png`).
"VideoBridge" remains the code name in packages, modules and docs. Do not rename code.

**TV text entry uses the app's own keyboard** (`core:tv-designsystem` → `TvKeyboard`), never the
system keyboard: Down past its last row closes it and moves to the next field, Up past its first
row closes it and returns to the field. Reuse it for every TV text field (search, rename, …).

## Current status

- **Confirmed on the real phone and TV (2026-10-09):** sign-in, connecting the TV with the
  4-digit code, saving a link on the phone, the link listed on the TV, playback on the TV, and
  the shared resume point.
- Also seen on hardware: the poster-card grid, size/format on a newly saved video, and the TV
  settings side panel with the installed video apps.
- Built but **not yet confirmed on hardware:** the app's own resumable downloader (Stop / Resume /
  Delete), the TV hold-OK menu fix, search, the phone player, playing through a chosen other app.
  See the two addenda in `docs/phase-reports/PHASE-04-05-report.md`.
- **Edit generated Kotlin by rewriting whole files or matching single short lines**: Spotless
  reformats multi-line constructs, so multi-line string patches silently miss.
- Still not built: realtime (the TV polls every 4 s), offline library, link inspection, preview
  pictures, the real download engine (Phase 6), and Phases 7–18.
- `dist/Dekho-phone.apk` and `dist/Dekho-tv.apk` are debug builds tied to this Mac's LAN address.
  **A standalone APK needs the backend hosted online first** (not done).
- The owner wants the app finished as fast as possible: prefer the shortest path to the MVP
  (save link on phone → appears on TV → play → download) and defer polish, saying what was deferred.
- Test devices: OnePlus CPH2619 (Android 16, USB adb) and Xiaomi MiTV-AXSO2 (Android 9, network
  adb). No emulators. Apps are built with `-PVB_DEV_HOST=<Mac LAN IP>`. **Do not send key
  presses to the TV or phone over adb without checking the screen first** — the owner uses them
  while work is in progress (a stray OK once signed the TV out).
- Known issues carried forward: the Atlas password was shared in chat and should be rotated;
  unverified numbers and weak passwords are an accepted risk (ADR-0008); work is uncommitted
  (owner will handle git later).
