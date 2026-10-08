# Phase 2 — Authentication (Mobile Number + OTP)

> **Implement this phase only. Do not start Phase 3. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-02-authentication.md
and docs/phase-reports/PHASE-01-report.md. We are implementing Phase 2 (Authentication) only.
Inspect the current backend and Android code first. Then give me a numbered plan: Prisma models
and migration, backend modules/endpoints/guards/rate limits, SMS provider abstraction, Android
token storage + interceptor/authenticator, phone screens, TV screens, and the tests for each step.
Call out anything in the phase file that conflicts with the existing code. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement the Phase 2 plan step by step, backend first (models → services → controllers →
guards → rate limits → tests), then Android (token store → network auth → repository → phone UI
→ TV UI → tests). Build and test after each step and commit. Never log OTPs or tokens.
```

**Prompt C — verify & report**
```
Run the Phase 2 verification commands and the full test suites. Demonstrate with curl the full
flow: request OTP → verify → call /users/me → refresh → reuse old refresh token (must revoke) →
logout. Tick every acceptance criterion with evidence, write docs/phase-reports/PHASE-02-report.md,
update CLAUDE.md status, and stop.
```

**Prompt D — fix**
```
<paste failure>. Find the root cause first, explain it briefly, fix with the smallest change,
re-run the failing test and the full suite, commit.
```

---

## 1. Goal

A user can sign in on the **phone** with a mobile number + 6-digit OTP, and on the **TV** with
the same flow using a remote-friendly keypad. The backend issues short-lived access tokens and
rotating refresh tokens bound to a session and a device. Sessions can be listed, logged out
and revoked. Abuse is rate-limited. Unauthorized API access is rejected.

## 2. Prerequisites
Phase 1 complete: backend skeleton, Prisma, Redis, error envelope, Android modules, flavors.

## 3. Scope

**In:** users, OTP requests, sessions, minimal devices table (registration at login), JWT
access tokens, refresh rotation + reuse detection, logout/logout-all, session listing and
revocation, rate limiting, SMS provider abstraction, `/users/me`, phone login UI, TV login UI,
token storage, automatic refresh, auth state handling.

**Out:** TV pairing (Phase 3), device rename/remove UI (Phase 3), account deletion (Phase 9),
admin auth (Phase 13).

---

## 4. Flow

```
Phone/TV                           Backend                               SMS provider
   │ POST /auth/otp/request {phone, installId}                              │
   │──────────────────────────────►│ normalize phone, rate-limit            │
   │                               │ invalidate previous pending OTPs       │
   │                               │ create otp_request (HMAC hash)         │
   │                               │──────── send "123456 is your code" ───►│
   │◄── 200 {otpRequestId, expiresAt, resendAvailableAt}                    │
   │ POST /auth/otp/verify {otpRequestId, phone, code, device{…}}           │
   │──────────────────────────────►│ check expiry/attempts, timing-safe cmp │
   │                               │ upsert user (isNewUser), upsert device │
   │                               │ create session + refresh token         │
   │◄── 200 {accessToken, refreshToken, user, device}                       │
   │ … API calls with Bearer access token (15 min)                          │
   │ POST /auth/refresh {refreshToken}  → rotate, return new pair           │
```

---

## 5. Data model (Prisma → migration `0002_auth`)

```prisma
enum UserStatus { ACTIVE SUSPENDED DELETION_PENDING DELETED }
enum DeviceType { PHONE ANDROID_TV }
enum OtpPurpose { LOGIN }
enum SessionRevokeReason { LOGOUT LOGOUT_ALL REUSE_DETECTED DEVICE_REMOVED ADMIN EXPIRED USER_SUSPENDED }

model User {
  id            String     @id @db.Uuid
  phoneE164     String     @unique @map("phone_e164")
  displayName   String?    @map("display_name") @db.VarChar(60)
  status        UserStatus @default(ACTIVE)
  tokenVersion  Int        @default(0) @map("token_version")   // bump to invalidate all access tokens
  lastLoginAt   DateTime?  @map("last_login_at") @db.Timestamptz
  createdAt     DateTime   @default(now()) @map("created_at") @db.Timestamptz
  updatedAt     DateTime   @updatedAt @map("updated_at") @db.Timestamptz
  devices  Device[]
  sessions Session[]
  @@map("users")
}

model OtpRequest {
  id              String     @id @db.Uuid
  phoneE164       String     @map("phone_e164")
  purpose         OtpPurpose @default(LOGIN)
  codeHash        String     @map("code_hash")          // HMAC-SHA256(OTP_PEPPER, id + ":" + code), hex
  expiresAt       DateTime   @map("expires_at") @db.Timestamptz
  attempts        Int        @default(0)
  maxAttempts     Int        @default(5) @map("max_attempts")
  consumedAt      DateTime?  @map("consumed_at") @db.Timestamptz
  invalidatedAt   DateTime?  @map("invalidated_at") @db.Timestamptz   // superseded by a resend
  requestIpHash   String?    @map("request_ip_hash")    // SHA-256(ip + IP_HASH_SALT) — no raw IPs
  installId       String?    @map("install_id")
  provider        String
  providerMsgId   String?    @map("provider_msg_id")
  deliveryStatus  String?    @map("delivery_status")
  createdAt       DateTime   @default(now()) @map("created_at") @db.Timestamptz
  @@index([phoneE164, createdAt])
  @@map("otp_requests")
}

model Device {
  id            String     @id @db.Uuid
  userId        String     @map("user_id") @db.Uuid
  installId     String     @map("install_id")           // random UUID created by the app on first launch
  type          DeviceType
  name          String     @db.VarChar(60)
  manufacturer  String?    @db.VarChar(60)
  model         String?    @db.VarChar(60)
  osVersion     String?    @map("os_version") @db.VarChar(30)
  appVersion    String?    @map("app_version") @db.VarChar(30)
  lastSeenAt    DateTime?  @map("last_seen_at") @db.Timestamptz
  revokedAt     DateTime?  @map("revoked_at") @db.Timestamptz
  createdAt     DateTime   @default(now()) @map("created_at") @db.Timestamptz
  updatedAt     DateTime   @updatedAt @map("updated_at") @db.Timestamptz
  user     User      @relation(fields: [userId], references: [id])
  sessions Session[]
  @@unique([userId, installId])
  @@index([userId])
  @@map("devices")
}

model Session {
  id                    String               @id @db.Uuid
  userId                String               @map("user_id") @db.Uuid
  deviceId              String               @map("device_id") @db.Uuid
  refreshTokenHash      String               @unique @map("refresh_token_hash")        // SHA-256 hex
  prevRefreshTokenHash  String?              @unique @map("prev_refresh_token_hash")
  rotatedAt             DateTime?            @map("rotated_at") @db.Timestamptz
  expiresAt             DateTime             @map("expires_at") @db.Timestamptz
  lastUsedAt            DateTime?            @map("last_used_at") @db.Timestamptz
  userAgent             String?              @map("user_agent") @db.VarChar(200)
  revokedAt             DateTime?            @map("revoked_at") @db.Timestamptz
  revokeReason          SessionRevokeReason? @map("revoke_reason")
  createdAt             DateTime             @default(now()) @map("created_at") @db.Timestamptz
  user   User   @relation(fields: [userId], references: [id])
  device Device @relation(fields: [deviceId], references: [id])
  @@index([userId])
  @@index([deviceId])
  @@map("sessions")
}
```

Rule: **one active session per device**. Logging in again on the same device revokes the
previous session for that device (`revokeReason = LOGOUT`).

---

## 6. Configuration (add to zod schema + `.env.example`)

| Variable | Rule / default |
|---|---|
| `JWT_ACCESS_SECRET` | ≥ 32 bytes, required |
| `JWT_ACCESS_TTL_SECONDS` | default 900 |
| `REFRESH_TTL_DAYS_PHONE` | default 30 (sliding) |
| `REFRESH_TTL_DAYS_TV` | default 180 (sliding) |
| `REFRESH_REUSE_GRACE_SECONDS` | default 20 (concurrent refresh from the same client tolerated) |
| `OTP_PEPPER` | ≥ 32 bytes, required |
| `IP_HASH_SALT` | ≥ 16 bytes, required |
| `OTP_TTL_SECONDS` | default 300 |
| `OTP_LENGTH` | default 6 |
| `OTP_MAX_ATTEMPTS` | default 5 |
| `SMS_PROVIDER` | `dev \| msg91 \| twilio` (`dev` forbidden when `APP_ENV=production`) |
| `MSG91_AUTH_KEY`, `MSG91_TEMPLATE_ID`, `MSG91_SENDER_ID` | required if provider = msg91 |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` / `TWILIO_VERIFY_SID` | required if provider = twilio |
| `OTP_TEST_NUMBERS` | dev/staging only, e.g. `+919999900001:123456,+919999900002:654321`; boot **fails** if set in production |
| `DEFAULT_PHONE_REGION` | default `IN` |

> India note: commercial SMS must use a DLT-registered sender ID and template (TRAI). Put the
> DLT template ID in config, not code. Document it in `docs/ops/sms.md`.

---

## 7. API contract

All bodies JSON. All errors use the envelope.

### `POST /api/v1/auth/otp/request` (public)
```json
// request
{ "phone": "9876543210", "countryHint": "IN", "installId": "0192…", "platform": "android-phone" }
// 200 — identical shape whether or not the number has an account (no enumeration)
{ "otpRequestId": "0192…", "expiresAt": "…", "resendAvailableAt": "…", "codeLength": 6 }
```
Errors: `VALIDATION_FAILED` (`details.fields.phone = "INVALID_PHONE"`), `RATE_LIMITED`
(`details.retryAfterSeconds`, `Retry-After` header), `SMS_DELIVERY_FAILED` (503).

Behaviour: normalize to E.164; reject non-mobile numbers where libphonenumber can tell;
mark previous pending OTPs for that phone `invalidated_at = now`; generate the code with
`crypto.randomInt`; store only the HMAC; send via provider; **for `OTP_TEST_NUMBERS`, skip
sending and use the configured code**.

### `POST /api/v1/auth/otp/verify` (public)
```json
// request
{
  "otpRequestId": "0192…",
  "phone": "+919876543210",
  "code": "123456",
  "device": { "installId": "0192…", "type": "PHONE", "name": "Pixel 8", "manufacturer": "Google",
              "model": "Pixel 8", "osVersion": "15", "appVersion": "0.2.0" }
}
// 200
{
  "accessToken": "eyJ…", "accessTokenExpiresAt": "…",
  "refreshToken": "vbr_…", "refreshTokenExpiresAt": "…",
  "user":   { "id": "…", "phoneMasked": "+91******3210", "displayName": null, "isNewUser": true },
  "device": { "id": "…", "type": "PHONE", "name": "Pixel 8" },
  "session": { "id": "…" }
}
```
Errors: `OTP_INVALID` (400, `details.attemptsRemaining`), `OTP_EXPIRED` (400),
`OTP_TOO_MANY_ATTEMPTS` (429), `OTP_ALREADY_USED` (400), `OTP_NOT_FOUND` (400 — also used
when phone doesn't match the request, to avoid oracle behaviour), `USER_SUSPENDED` (403),
`RATE_LIMITED`.

Behaviour (single DB transaction with `SELECT … FOR UPDATE` on the otp row):
increment `attempts` **before** comparing; timing-safe compare; on success set `consumed_at`,
upsert user, upsert device on `(userId, installId)` (clear `revoked_at`? **No** — a revoked
device re-logging in gets a **new** device row; set unique constraint accordingly by
including only non-revoked rows via a partial unique index in a raw SQL migration:
`CREATE UNIQUE INDEX devices_user_install_active ON devices(user_id, install_id) WHERE revoked_at IS NULL;`
and drop the Prisma `@@unique`), revoke the device's previous session, create the new
session, update `last_login_at`.

### `POST /api/v1/auth/refresh` (public, body token)
```json
{ "refreshToken": "vbr_…" }   →   200 same token pair shape as verify (without user/device)
```
Logic:
1. Hash the token. Find session by `refreshTokenHash`.
   - Found, not revoked, not expired, user ACTIVE, device not revoked → rotate: new random
     token, `prevRefreshTokenHash = old`, `refreshTokenHash = new`, `rotatedAt = now`,
     extend `expiresAt` (sliding), `lastUsedAt = now`. Return new pair.
2. Not found → look up by `prevRefreshTokenHash`.
   - Found and `now - rotatedAt ≤ REFRESH_REUSE_GRACE_SECONDS` → **return 409
     `REFRESH_RACE`** (client should retry with the newest token it holds). Do not revoke.
   - Found and outside grace → **reuse detected**: revoke session (`REUSE_DETECTED`), write a
     security log line (no token), return 401 `SESSION_REVOKED`.
3. Otherwise 401 `REFRESH_INVALID`.

Token format: `vbr_` + base64url(32 random bytes).

### `POST /api/v1/auth/logout` (auth) → 204. Revokes the current session.
### `POST /api/v1/auth/logout-all` (auth) → 204. Revokes all sessions; `tokenVersion++`.
### `GET /api/v1/auth/sessions` (auth)
`{ items: [{ id, device: {id,name,type}, createdAt, lastUsedAt, current: true|false }] }`
### `DELETE /api/v1/auth/sessions/:id` (auth) → 204 (only own sessions; otherwise 404).
### `GET /api/v1/users/me` (auth) → `{ id, phoneMasked, displayName, createdAt }`
### `PATCH /api/v1/users/me` (auth) `{ displayName }` (1–60 chars, trimmed) → user.

---

## 8. Backend implementation tasks

1. **Crypto utils** (`common/utils/crypto.ts`): `randomToken(bytes)`, `sha256Hex`,
   `hmacSha256Hex(key, msg)`, `timingSafeEqualHex`. Unit tests.
2. **Phone utils**: `normalizePhone(input, regionHint) → {e164} | INVALID_PHONE`, `maskPhone`.
3. **SMS provider abstraction** (`modules/auth/sms/`):
   ```ts
   export interface SmsProvider { send(toE164: string, code: string, ctx: { requestId: string }): Promise<{ providerMsgId?: string }> }
   ```
   `DevSmsProvider` (no network; never logs the code; returns fake id), `Msg91SmsProvider`,
   `TwilioSmsProvider` (HTTP clients with 5 s timeout, 1 retry on 5xx, errors mapped to
   `SMS_DELIVERY_FAILED`). Provider selected by config through a factory provider.
4. **Rate limiter** (`common/rate-limit/`): Redis-backed fixed-window + cooldown using a Lua
   script (atomic INCR + EXPIRE). API: `consume(key, limit, windowSec) → {allowed, retryAfterSec}`.
   A `@RateLimit()` decorator/guard for simple per-IP limits, and direct service calls for
   composite limits. Limits:

   | Action | Key | Limit |
   |---|---|---|
   | OTP request | `otp:req:phone:{e164}` | cooldown 30 s → 60 s → 120 s → 300 s (escalates per send in the last hour); max 5/hour, 10/day |
   | OTP request | `otp:req:ip:{ipHash}` | 20/hour |
   | OTP request | `otp:req:install:{installId}` | 10/hour |
   | OTP verify | per otp row | 5 attempts (DB) |
   | OTP verify failures | `otp:fail:phone:{e164}` | 10/hour → 1 h lock |
   | OTP verify | `otp:ver:ip:{ipHash}` | 60/hour |
   | Refresh | `auth:refresh:ip:{ipHash}` | 120/hour |

5. **Token service**: sign/verify access JWT (HS256 via `@nestjs/jwt`) with claims
   `sub, did, sid, typ:"access", ver` (user tokenVersion), `iat`, `exp`, `iss:"videobridge"`, `aud:"videobridge-app"`.
6. **OtpService**: `requestOtp`, `verifyOtp` as specified (inject `Clock`).
7. **SessionService**: `createSession`, `rotate`, `revoke`, `revokeAllForUser`, `listForUser`.
   On revoke write `auth:revoked:sid:{sid}` to Redis with TTL = access token TTL + 60 s.
8. **`JwtAuthGuard`** (global, opt-out with `@Public()`): verify JWT → check Redis revoked-sid
   key → check `ver` against cached user tokenVersion (`auth:user:ver:{userId}`, 60 s TTL,
   fallback DB) → check user status → attach `req.principal = { userId, deviceId, sessionId }`.
   `@CurrentPrincipal()` param decorator. Update `devices.last_seen_at` at most once per 5 min
   (Redis throttle key) — never per request.
9. **Controllers** `AuthController`, `UsersController` with Swagger decorators and DTOs.
10. **Security logging**: structured `security` log events: `otp_requested`, `otp_failed`,
    `otp_locked`, `login_success`, `refresh_reuse_detected`, `logout_all` — masked phone,
    ip hash, userId, deviceId; never codes/tokens.
11. **Cleanup job**: `@nestjs/schedule` cron hourly — delete `otp_requests` older than 7 days;
    mark sessions expired (`revokeReason = EXPIRED`).
12. Regenerate OpenAPI.

### Backend tests
- Unit: phone normalization (valid IN mobile, landline rejected, with/without +91, spaces),
  OTP hashing/verification, rate limiter (fake Redis or real test Redis), token service
  (expired, wrong typ, wrong aud, tampered), refresh rotation and reuse logic with `FakeClock`.
- E2E (`test/auth.e2e-spec.ts`, real Postgres/Redis, `OTP_TEST_NUMBERS` set):
  - happy path request → verify → `/users/me` → refresh → `/users/me` with new token.
  - wrong code ×5 → `OTP_TOO_MANY_ATTEMPTS`; correct code afterwards still rejected.
  - expired OTP (FakeClock or TTL 1 s) → `OTP_EXPIRED`.
  - resend within cooldown → `RATE_LIMITED` with `retryAfterSeconds`.
  - resend after cooldown invalidates the first OTP (`OTP_NOT_FOUND`/invalid for old id).
  - same response shape for existing and new numbers.
  - old refresh token after grace → `SESSION_REVOKED`, and the new token is now also dead.
  - two quick concurrent refreshes → one success, one `REFRESH_RACE` (no revocation).
  - logout → access token rejected within one request (Redis revoked key) and refresh rejected.
  - logout-all revokes every session; tokens from other devices fail.
  - no token / malformed / expired → 401 `UNAUTHENTICATED` / `TOKEN_EXPIRED`.
  - user A cannot delete user B's session (404).
  - log capture test: run the OTP flow and assert that no log line contains the code or token.

---

## 9. Android implementation tasks

### 9.1 Shared (core modules)
- `core:datastore`
  - `InstallIdProvider`: UUID created on first launch, stored in Preferences DataStore.
  - `EncryptedTokenStore`: stores `{accessToken, accessExp, refreshToken, refreshExp, userId, deviceId, sessionId}`
    serialized → encrypted with **Tink AEAD** (`AndroidKeysetManager` with master key in
    Android Keystore) → DataStore. API: `tokens: Flow<Tokens?>`, `save()`, `clear()`.
    (Do not use the deprecated `EncryptedSharedPreferences`.)
- `core:network`
  - `AuthApi` (Retrofit): `requestOtp`, `verifyOtp`, `refresh`, `logout`, `logoutAll`,
    `sessions`, `deleteSession`; `UsersApi`: `me`, `updateMe`.
  - `AuthInterceptor`: adds `Authorization` for non-public calls.
  - `TokenAuthenticator` (OkHttp `Authenticator`): on 401 `TOKEN_EXPIRED`, **single-flight**
    refresh guarded by a `Mutex`; if another call already refreshed (token changed), retry
    with the new token; on `REFRESH_RACE` re-read the store and retry once; on
    `REFRESH_INVALID` / `SESSION_REVOKED` → clear tokens and emit `SessionEvent.LoggedOut(reason)`.
    Use a separate OkHttp client without the authenticator for the refresh call.
  - Header interceptor adds `X-Device-Id` once known.
- `core:data`
  - `AuthRepository`: `requestOtp(phone)`, `verifyOtp(...)`, `logout()`, `logoutAll()`.
  - `SessionManager`: `authState: StateFlow<AuthState>` (`Unknown`, `LoggedOut(reason?)`,
    `LoggedIn(userId, deviceId)`); on logout clears tokens and (later phases) the Room DB.
  - `DeviceInfoProvider`: name from `Settings.Global.DEVICE_NAME` fallback `Build.MODEL`,
    manufacturer, model, `Build.VERSION.RELEASE`, app version; type PHONE or ANDROID_TV
    (TV detected by `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` or
    `PackageManager.FEATURE_LEANBACK`).

### 9.2 Phone UI (`app-phone`)
Navigation (Compose Navigation, type-safe routes): `Startup → (Login graph | Main graph)`.
- **PhoneEntryScreen**: country code chip (+91 default, picker optional), numeric field with
  `KeyboardType.Phone`, live validation, "Send code" button (disabled while invalid/loading),
  terms/privacy text links (placeholders). Errors: invalid number, rate limited ("Try again in 42 s").
- **OtpScreen**: 6 single-digit boxes (one hidden `BasicTextField` driving the boxes),
  auto-submit on 6th digit, paste support, resend button with countdown from
  `resendAvailableAt`, "Change number", attempts remaining, expired state with "Send new code".
  Optional: SMS User Consent API (`play-services-auth-api-phone`) to auto-fill — must not
  require READ_SMS permission.
- **HomePlaceholderScreen**: "Signed in as +91******3210", "Sessions" list (from
  `/auth/sessions`, current marked), "Log out", "Log out everywhere".
- ViewModels expose `UiState` data classes; one-off effects via `Channel`.

### 9.3 TV UI (`app-tv`)
- **TvLoginScreen**: big title, phone display field, **on-screen numeric keypad** (3×4 grid:
  1–9, ⌫, 0, ✓) built from focusable `Surface`/`Button` from tv-material; initial focus on
  "1"; D-pad navigates the grid; number keys on the remote also type digits
  (`onKeyEvent` for `KEYCODE_0..9`); Back deletes a digit, long-Back exits. Same keypad
  reused for OTP entry with a resend countdown.
- A secondary notice: "Faster: pair with your phone (coming soon)" — replaced in Phase 3.
- After login: **TvHomePlaceholder** showing user + "Sign out".
- Focus must be obvious: scale 1.1 + border on focus. Text ≥ 18sp body, 32sp+ titles.

### 9.4 Android tests
- `TokenAuthenticatorTest` with MockWebServer: expired → one refresh → retried; 5 parallel
  401s → exactly one refresh call; refresh revoked → logged-out event, no infinite loop.
- `EncryptedTokenStoreTest` (Robolectric or instrumented): round-trip, clear.
- `PhoneEntryViewModelTest`, `OtpViewModelTest` (Turbine): validation, countdown with
  `TestScope` virtual time, error mapping for each error code.
- Compose UI tests: OTP boxes accept paste; TV keypad D-pad navigation (send `KEYCODE_DPAD_*`)
  types digits.

---

## 10. File structure (new/changed)

```
backend/prisma/schema.prisma (users, otp_requests, devices, sessions) + migrations/…_auth/
backend/src/common/utils/{crypto.ts,phone.ts}
backend/src/common/rate-limit/{rate-limit.service.ts,rate-limit.lua,rate-limit.guard.ts,rate-limit.decorator.ts}
backend/src/common/decorators/{public.decorator.ts,current-principal.decorator.ts}
backend/src/modules/auth/{auth.module.ts,auth.controller.ts,otp.service.ts,session.service.ts,token.service.ts,jwt-auth.guard.ts,dto/*.ts}
backend/src/modules/auth/sms/{sms-provider.ts,dev.sms-provider.ts,msg91.sms-provider.ts,twilio.sms-provider.ts,sms.module.ts}
backend/src/modules/users/{users.module.ts,users.controller.ts,users.service.ts,dto/*.ts}
backend/src/modules/devices/{devices.module.ts,devices.service.ts}        # upsert only in this phase
backend/test/{auth.e2e-spec.ts,helpers/auth.helper.ts,helpers/log-capture.ts}
android/core/datastore/…/{InstallIdProvider.kt,EncryptedTokenStore.kt,TinkModule.kt}
android/core/network/…/{AuthApi.kt,UsersApi.kt,AuthInterceptor.kt,TokenAuthenticator.kt,dto/Auth*.kt}
android/core/data/…/{AuthRepository.kt,SessionManager.kt,DeviceInfoProvider.kt}
android/app-phone/…/auth/{PhoneEntryScreen.kt,PhoneEntryViewModel.kt,OtpScreen.kt,OtpViewModel.kt}
android/app-tv/…/auth/{TvLoginScreen.kt,TvNumericKeypad.kt,TvLoginViewModel.kt}
docs/ops/sms.md
```

---

## 11. Security requirements

- OTP stored only as HMAC with a server pepper; compared timing-safely; attempts incremented
  before comparison inside a row lock.
- No user enumeration via responses or timing (do the same DB work and similar latency).
- No OTP, token, or full phone number in logs, error messages, analytics, or crash reports.
- Refresh tokens stored only as SHA-256; rotated every use; reuse detection revokes the session.
- Access token validation checks revocation and token version, not just signature.
- `OTP_TEST_NUMBERS` and `SMS_PROVIDER=dev` impossible in production (boot-time check + test).
- Rate limits keyed on hashed IP (respect `TRUST_PROXY` for `X-Forwarded-For`).
- Android: tokens only in Tink-encrypted DataStore; `android:allowBackup` either false or
  backup rules exclude the token store (`dataExtractionRules`).

---

## 12. Verification commands

```bash
make dev
P=+919999900001   # a test number from OTP_TEST_NUMBERS
R=$(curl -s -XPOST localhost:3000/api/v1/auth/otp/request -H 'content-type: application/json' \
   -d "{\"phone\":\"$P\",\"installId\":\"0192aaaa-0000-7000-8000-000000000001\"}")
echo "$R" | jq
ID=$(echo "$R" | jq -r .otpRequestId)
T=$(curl -s -XPOST localhost:3000/api/v1/auth/otp/verify -H 'content-type: application/json' \
   -d "{\"otpRequestId\":\"$ID\",\"phone\":\"$P\",\"code\":\"123456\",\"device\":{\"installId\":\"0192aaaa-0000-7000-8000-000000000001\",\"type\":\"PHONE\",\"name\":\"curl\"}}")
AT=$(echo "$T" | jq -r .accessToken); RT=$(echo "$T" | jq -r .refreshToken)
curl -s localhost:3000/api/v1/users/me -H "authorization: Bearer $AT" | jq
curl -s -XPOST localhost:3000/api/v1/auth/refresh -H 'content-type: application/json' -d "{\"refreshToken\":\"$RT\"}" | jq
sleep 25; curl -s -XPOST localhost:3000/api/v1/auth/refresh -H 'content-type: application/json' -d "{\"refreshToken\":\"$RT\"}" | jq   # SESSION_REVOKED
make lint && make test
grep -rniE '"(otp|code|refreshToken|accessToken)"' <captured-logs> || echo "no secrets in logs"
```

## 13. Acceptance criteria

- [ ] Migration `…_auth` applies on an empty DB and on a Phase-1 DB.
- [ ] OTP request/verify works with test numbers; real provider adapters compile and are unit-tested with mocked HTTP.
- [ ] OTP expiry, max attempts, resend cooldown (escalating), per-phone/IP/install limits enforced with correct `Retry-After`.
- [ ] Responses don't reveal whether a number is registered.
- [ ] Access tokens expire after 15 min; refresh rotates; reuse after grace revokes; concurrent refresh handled.
- [ ] Logout, logout-all and session deletion take effect immediately for access tokens.
- [ ] All protected endpoints reject missing/invalid/expired tokens with the envelope.
- [ ] One active session per device; re-login on the same device replaces it.
- [ ] Logs contain no OTPs/tokens (automated test).
- [ ] Phone: number → OTP → home works on emulator; resend countdown; error states; logout returns to login; app restart keeps the user signed in.
- [ ] Phone: token refresh happens transparently (verify by setting `JWT_ACCESS_TTL_SECONDS=30`).
- [ ] TV: full login with D-pad only and with remote number keys; focus always visible.
- [ ] All new unit/e2e/Android tests pass; previous tests still pass; OpenAPI regenerated.

## 14. Manual test script

1. Phone: enter `99999 00001` → code `123456` → home shows masked number.
2. Kill & relaunch → still signed in. Set access TTL 30 s, wait 40 s, open Sessions → loads (silent refresh).
3. Enter wrong code 5 times on a fresh request → locked message; resend after cooldown works.
4. TV emulator: sign in with the same test number using only the D-pad; then sign in on the phone again → both sessions listed on the phone; delete the TV session → TV gets logged out on its next API call.
5. "Log out everywhere" on phone → TV and phone both return to login.

## 15. Pitfalls

- OkHttp `Authenticator` runs on an OkHttp thread — use `runBlocking` carefully with a
  `Mutex`, or a synchronous refresh call; never call the authenticated client from inside it.
- Don't decide "expired" on the client by clock alone (TV clocks are often wrong); rely on 401s,
  and use expiry only as a hint for proactive refresh.
- Prisma partial unique index must be added via raw SQL in the migration; remove the
  conflicting `@@unique` from the schema and document it.
- Twilio Verify manages its own codes; if you use Verify instead of plain SMS, the HMAC
  storage path changes — keep the abstraction so `OtpService` can delegate verification.
