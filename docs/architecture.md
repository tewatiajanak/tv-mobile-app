# VideoBridge — Architecture

Living document. The contracts (naming, API format, events) are in
[phases/00-architecture-and-conventions.md](phases/00-architecture-and-conventions.md);
decisions are in [decisions/](decisions/).

## Current state — Phase 3: accounts, sign-in, TV pairing

- **Backend:** health endpoints, plus accounts: register / sign in with mobile number and
  password (ADR-0008), 15-minute access tokens, rotating refresh tokens, sessions per device.
  Every route needs a token unless marked `@Public()`.
- **Phone and TV apps:** a sign-in / create-account screen, then a placeholder home with the
  user's name at the top. The session is stored encrypted and survives restarts.
- Database is **MongoDB Atlas** (ADR-0006); **no Redis** (ADR-0007), so rate limits live in MongoDB.
- **TV pairing:** a signed-out TV shows a QR code and an 8-character code; a signed-in phone
  claims the code and approves; the TV, polling every 2 s, picks up its sign-in exactly once.
  The phone lists, renames and removes devices.
- **Library:** the phone saves links (paste or Share); the backend stores them; the TV lists
  them (polling every 4 s) and plays them with Media3 directly from the source. The resume
  point is stored on the video and shared by all devices.
- No realtime channel, no local cache of the library, no link inspection, no downloads yet.

### How staying signed in works
1. Sign-in returns an access token (15 min) and a refresh token (180 days, sliding).
2. The app saves both encrypted (Tink AEAD, key in the Android Keystore) and loads them at
   launch, so the home screen appears without any network call.
3. When the backend answers 401, OkHttp's authenticator refreshes once (parallel requests
   share that one refresh), saves the new pair, and retries. The user sees nothing.
4. Each refresh token works once. Using a replaced one revokes the session.
5. If the refresh itself is refused, the app clears the session and shows the sign-in screen.

## System

```
 ┌──────────────────────┐        REST (HTTPS) + WebSocket (WSS)        ┌──────────────────────────┐
 │  Android Phone app   │ ───────────────────────────────────────────► │   Cloud backend (NestJS)  │
 │  Kotlin / Compose    │ ◄─────────────────────────────────────────── │   /api/v1/*   /ws         │
 │  Room = local truth  │                                              │                           │
 └──────────────────────┘                                              │  Phase 1: health only     │
                                                                       │                           │
 ┌──────────────────────┐        REST (HTTPS) + WebSocket (WSS)        │                           │
 │  Android TV app      │ ───────────────────────────────────────────► │                           │
 │  Compose for TV      │ ◄─────────────────────────────────────────── └──────┬─────────────┬──────┘
 │  Media3 · Downloads  │                                                     │             │
 └─────────┬────────────┘                                              ┌──────▼─────┐ ┌─────▼─────┐
           │  video bytes (later phases; never via the backend)        │  MongoDB   │ │   Redis   │
           ▼                                                           └────────────┘ └───────────┘
 ┌──────────────────────┐
 │ Source video server  │
 └──────────────────────┘
```

**Golden rule:** the backend never stores or proxies video bytes (ADR-0005).

## Backend (`backend/`)

| Path | Role |
|---|---|
| `src/main.ts`, `src/app.setup.ts` | Bootstrap. `configureApp` is shared by the server, the e2e tests and the OpenAPI export so they can't drift. |
| `src/config/` | zod schema for every environment variable; the process refuses to start on an invalid one and names it without printing its value. |
| `src/infra/prisma`, `redis` | Clients. A dependency that is down at boot does not crash the process; readiness reports it. |
| `src/infra/logger` | pino. JSON outside a developer terminal. One redaction list; URLs are logged without query strings. |
| `src/infra/clock`, `ids` | Injectable `Clock`; `newId()` (UUIDv7). |
| `src/common/errors`, `filters`, `pipes`, `middleware` | Error-code registry, the error envelope for every non-2xx, validation, request ids. |
| `src/modules/health` | Liveness and readiness. |
| `src/modules/auth`, `users`, `devices` | Accounts, sessions, tokens, the global auth guard. |
| `src/common/rate-limit` | Fixed-window counters in MongoDB. |
| `src/modules/test-support` | Routes registered only when `NODE_ENV=test`, for e2e tests of the filter and pipe. |

Request path: `X-Request-Id` is read or created → bound to every log line → echoed in the
response and in the error envelope.

## Android (`android/`)

Convention plugins in `build-logic/convention` keep module build files to a few lines:
`videobridge.android.application`, `.android.library`, `.android.compose`, `.android.hilt`,
`.android.room`, `.jvm.library`. Every Android module has the `env` flavors `dev | staging | prod`.

| Module | Contents now |
|---|---|
| `core:model` | Pure Kotlin models (`HealthStatus`). |
| `core:common` | Dispatcher qualifiers, `AppResult`/`AppError`, Timber setup. |
| `core:network` | OkHttp + Retrofit + kotlinx.serialization, standard headers, error-envelope parser, `HealthApi`. |
| `core:database` | Room database (v1, schema exported) with a key/value table. |
| `core:datastore` | Preferences DataStore scaffold. |
| `core:data` | Repositories (`HealthRepository`). |
| `core:player` | Media3 dependencies and `PlayerFactory` (unused until Phase 5). |
| `core:designsystem`, `core:tv-designsystem` | Phone (Material 3) and TV (`androidx.tv.material3`) themes. |
| `core:testing` | `MainDispatcherRule`, fakes. |
| `feature:auth` | Sign-in view models shared by both apps. |
| `app-phone`, `app-tv` | Application (Hilt + WorkManager factory), one activity, sign-in and home screens. |

UI follows MVVM with one-way data flow: a ViewModel exposes `StateFlow<UiState>`, composables
render it and send events back.

## Environments

| Env | Backend URL | Android flavor |
|---|---|---|
| Development | `http://10.0.2.2:3000` (emulator) or `http://<LAN-IP>:3000` | `dev` (cleartext allowed here only) |
| Staging | `https://api-staging.<domain>` | `staging` |
| Production | `https://api.<domain>` | `prod` |
