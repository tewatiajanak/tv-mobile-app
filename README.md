# Dekho

> The app is called **Dekho** for users. "VideoBridge" is the internal code name (package names,
> modules, docs). The logo source is `design/dekho-logo.png`.

Collect video links on an Android phone; play or download them on an Android TV. A cloud backend
keeps accounts, devices and the library in sync. It never stores or proxies video.

**Status:** Phase 1 (project foundation). The apps only show whether they can reach the backend.

- How the system is put together: [docs/architecture.md](docs/architecture.md)
- The build plan, one file per phase: [docs/phases/](docs/phases/)

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| MongoDB | Atlas (free tier is fine) | database — or run it locally with `make services` |
| Redis | 7, optional | not used unless `REDIS_URL` is set (ADR-0007) |
| Docker with Compose v2 | optional | only for `make services` / `make test-services` |
| Node.js | 22 LTS | backend |
| JDK | 17 or newer | Android build |
| Android SDK | platform 36, build-tools 36 | Android build (`android/local.properties` → `sdk.dir=…`) |
| Emulators | one phone, one Android TV | running the apps |

The Makefile needs a POSIX shell. On Windows, use WSL 2.

## Quick start

```bash
cp backend/.env.example backend/.env     # then set DATABASE_URL
cd backend && npm ci && cd ..

make dev        # syncs the schema to MongoDB, runs the backend with reload
```

Then, in another terminal:

```bash
curl localhost:3000/api/v1/health         # {"status":"ok","env":"development",...}
curl localhost:3000/api/v1/health/ready   # {"status":"ok","checks":{"db":"up","redis":"disabled"}}
open http://localhost:3000/api/docs       # Swagger UI
```

Build and install the apps (emulators running):

```bash
make android
adb -s <phone-emulator> install -r android/app-phone/build/outputs/apk/dev/debug/*.apk
adb -s <tv-emulator>    install -r android/app-tv/build/outputs/apk/dev/debug/*.apk
```

Each app should show **Backend: OK (development)**.

## Commands

| Command | Does |
|---|---|
| `make dev` | sync the schema to MongoDB, backend in watch mode |
| `make services` | optional: local MongoDB + Redis in Docker |
| `make down` | stop the Docker services |
| `make test` | backend unit + e2e tests, Android unit tests |
| `make lint` | ESLint, Prettier check, `tsc`; Spotless (ktlint), detekt, Android lint |
| `make migrate` | `prisma db push` (MongoDB has no migration files) |
| `make openapi` | regenerate `docs/api/openapi.json` |
| `make android` | assemble the `devDebug` phone and TV APKs |

E2E tests use their own database, set in `backend/.env.test` (for example a `videobridge_test`
database on the same Atlas cluster), so they never touch development data.

With Atlas, add your computer's public IP under **Network Access**, or every connection fails
with a TLS error.

## Using a real phone or TV

An emulator reaches your computer at `10.0.2.2`, which is the default. A real device needs your
computer's LAN address:

```bash
cd android
./gradlew :app-phone:assembleDevDebug :app-tv:assembleDevDebug -PVB_DEV_HOST=192.168.1.20
adb connect <tv-ip>      # then adb install as above
```

The device and the computer must be on the same Wi-Fi, and your firewall must allow incoming
connections on port 3000. The backend already listens on all interfaces. Plain `http` is allowed
only in the `dev` flavor.

## Layout

```
backend/    NestJS API (Prisma, MongoDB, Redis)
android/    app-phone, app-tv, core/* modules, build-logic (convention plugins)
infra/      Docker Compose for development and tests
docs/       architecture, decisions (ADRs), phase files and reports, OpenAPI
```
