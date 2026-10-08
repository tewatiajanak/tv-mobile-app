# Phase 3 — Device Management & TV Pairing

> **Implement this phase only. Do not start Phase 4. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-03-devices-and-tv-pairing.md
and the latest phase report. We are implementing Phase 3 (Device Management & TV Pairing) only.
Inspect the existing auth, devices and Android code first. Give me a numbered plan covering:
pairing_sessions model + migration, pairing endpoints and state machine, entitlement stub,
device endpoints, the authenticated WebSocket gateway with presence, Android WebSocket client,
TV pairing screen, phone QR scan + confirm flow, phone device list, and tests. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 3 step by step: backend pairing → devices → entitlement stub → WebSocket
gateway + presence → tests; then Android realtime client → TV pairing UI → phone scan/confirm UI →
device management UI → tests. Build, test and commit after each step.
```

**Prompt C — verify & report**
```
Run the Phase 3 verification commands and all test suites. Demonstrate the complete pairing flow
with curl/wscat and on the emulators. Tick the acceptance criteria with evidence, write
docs/phase-reports/PHASE-03-report.md, update CLAUDE.md, and stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

- A TV that is not signed in shows a **QR code and an 8-character code**. The user scans it
  (or types the code) on their signed-in phone, sees **"Connect this TV?"** with the TV's name
  and model, taps **Connect**, and the TV signs in within ~2 seconds — no typing on the TV.
- Users can see all their devices (phones and TVs), rename them, remove/revoke them, and see
  whether each is online.
- An authenticated WebSocket exists with presence, ready for sync in Phase 4.
- Device limits go through an `EntitlementsService` stub so Phase 8 only swaps the data source.

## 2. Prerequisites
Phase 2: users, sessions, devices (minimal), JWT guard, token handling on Android.

## 3. Scope
**In:** pairing sessions, QR/code generation, claim/approve/reject, TV token pickup, device
list/rename/revoke, presence over WebSocket, `DEVICE_*` events, entitlement stub with
`max_devices`/`max_tv_devices`, phone scanner, TV pairing screen.
**Out:** video sync events (Phase 4), full sync protocol (Phase 11), notifications (Phase 14).

---

## 4. Pairing protocol

```
TV (signed out)                      Backend                                  Phone (signed in)
 │ POST /pairing/sessions {tvDevice}   │                                          │
 │────────────────────────────────────►│ create PENDING, code ABCD-2345,          │
 │◄─ {pairingId, code, qrPayload,      │ pollToken (secret), expiresAt (+5 min)   │
 │    pollToken, expiresAt, pollIntervalMs}                                       │
 │ shows QR(qrPayload) + code          │                                          │
 │ GET /pairing/sessions/{id}/status   │     scans QR / types code                │
 │   (X-Pairing-Poll-Token) every 2 s  │◄──── POST /pairing/claim {code} ─────────│
 │                                     │ PENDING→CLAIMED(by user U)               │
 │                                     │──── {pairingId, tv{name,model,…}} ──────►│ "Connect this TV?"
 │                                     │◄──── POST /pairing/{id}/approve ─────────│  (or /reject)
 │                                     │ check entitlement max_tv_devices         │
 │                                     │ create TV device + session → APPROVED    │
 │ status → APPROVED + one-time tokens │                                          │
 │◄────────────────────────────────────│ → CONSUMED (tokens never returned again) │
 │ store tokens, connect WebSocket     │──── DEVICE_CONNECTED event ─────────────►│ "Living Room TV connected"
```

State machine: `PENDING → CLAIMED → APPROVED → CONSUMED`; any of `PENDING/CLAIMED →
REJECTED | EXPIRED | CANCELLED` (TV refreshes code → old one CANCELLED). A claim by a second
phone on an already CLAIMED session fails with `PAIRING_ALREADY_CLAIMED`.

Code format: 8 characters from an unambiguous alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`
(no 0/O/1/I/L), displayed `ABCD-2345`, input is case-insensitive and ignores dashes/spaces.
~40 bits of entropy + 5 min TTL + rate limits = not brute-forceable.

QR payload: `https://<app-link-domain>/pair?c=ABCD2345` (an Android App Link that opens the
phone app; if the app isn't installed it lands on a web page with install instructions). In
development use `videobridge://pair?c=ABCD2345`. The QR **never** contains the poll token.

---

## 5. Data model (migration `…_devices_pairing`)

```prisma
enum PairingStatus { PENDING CLAIMED APPROVED CONSUMED REJECTED EXPIRED CANCELLED }

model PairingSession {
  id               String        @id @db.Uuid
  codeHash         String        @unique @map("code_hash")        // HMAC(PAIRING_PEPPER, normalizedCode)
  pollTokenHash    String        @map("poll_token_hash")          // SHA-256
  status           PairingStatus @default(PENDING)
  tvInstallId      String        @map("tv_install_id")
  tvName           String        @map("tv_name") @db.VarChar(60)
  tvManufacturer   String?       @map("tv_manufacturer") @db.VarChar(60)
  tvModel          String?       @map("tv_model") @db.VarChar(60)
  tvOsVersion      String?       @map("tv_os_version") @db.VarChar(30)
  tvAppVersion     String?       @map("tv_app_version") @db.VarChar(30)
  claimedByUserId  String?       @map("claimed_by_user_id") @db.Uuid
  claimedByDeviceId String?      @map("claimed_by_device_id") @db.Uuid
  claimedAt        DateTime?     @map("claimed_at") @db.Timestamptz
  approvedAt       DateTime?     @map("approved_at") @db.Timestamptz
  createdDeviceId  String?       @map("created_device_id") @db.Uuid
  consumedAt       DateTime?     @map("consumed_at") @db.Timestamptz
  expiresAt        DateTime      @map("expires_at") @db.Timestamptz
  requestIpHash    String?       @map("request_ip_hash")
  createdAt        DateTime      @default(now()) @map("created_at") @db.Timestamptz
  updatedAt        DateTime      @updatedAt @map("updated_at") @db.Timestamptz
  @@index([status, expiresAt])
  @@map("pairing_sessions")
}
```

Device additions: `pairedViaPairingId String? @db.Uuid`, `nameCustomized Boolean @default(false)`.

Approved tokens are held **only in Redis** between approval and pickup:
`pairing:tokens:{pairingId}` (AES-GCM encrypted with a server key, TTL 120 s), deleted on pickup.

---

## 6. API contract

### `POST /api/v1/pairing/sessions` (public, TV)
```json
// request
{ "tv": { "installId": "…", "name": "Living Room TV", "manufacturer": "Sony", "model": "KD-55X80K", "osVersion": "12", "appVersion": "0.3.0" } }
// 201
{ "pairingId": "…", "code": "ABCD-2345", "qrPayload": "https://link.example.com/pair?c=ABCD2345",
  "pollToken": "vbp_…", "expiresAt": "…", "pollIntervalMs": 2000 }
```
Rate limits: 10/hour per `installId`, 30/hour per IP hash. Creating a new session for the
same `installId` cancels its previous PENDING/CLAIMED ones.

### `GET /api/v1/pairing/sessions/{id}/status` (public, header `X-Pairing-Poll-Token`)
- `200 { "status": "PENDING" | "CLAIMED" | "REJECTED" | "EXPIRED" | "CANCELLED" }`
- `200 { "status": "APPROVED", "auth": { accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user, device, session } }`
  — returned **exactly once**; the row moves to `CONSUMED` in the same transaction that
  deletes the Redis token blob. A second call returns `{ "status": "CONSUMED" }` without tokens.
- Wrong/missing poll token → 404 `NOT_FOUND` (don't reveal existence).
- Supports long-poll: `?waitMs=25000` holds the request until status changes or timeout
  (implemented with Redis pub/sub channel `pairing:{id}`). Plain 2 s polling must also work.

### `POST /api/v1/pairing/claim` (auth, PHONE devices only)
`{ "code": "abcd 2345" }` → `200 { "pairingId": "…", "tv": { name, manufacturer, model }, "expiresAt": "…" }`
Errors: `PAIRING_CODE_INVALID` (404 — also for expired/consumed), `PAIRING_ALREADY_CLAIMED` (409),
`FORBIDDEN` if caller is a TV. Rate limit: 10 claims / 10 min per user, 30/hour per IP hash,
and 5 failed codes / 10 min per user → 15 min lock.

### `POST /api/v1/pairing/{id}/approve` (auth, must be the claiming user)
Body optional `{ "name": "Bedroom TV" }` to rename while approving.
→ `200 { "device": {…} }`. Errors: `ENTITLEMENT_LIMIT` (`max_tv_devices` or `max_devices`,
with `details.limit`/`details.current`), `PAIRING_EXPIRED`, `NOT_FOUND`.
Inside one transaction with `SELECT … FOR UPDATE` on the user row: count active devices,
check limits, create the TV device (or reuse the active one with the same `installId` —
then revoke its old session), create session + refresh token, store tokens in Redis,
set APPROVED, publish `pairing:{id}`, emit `DEVICE_CONNECTED`.

### `POST /api/v1/pairing/{id}/reject` (auth, claiming user) → 204 (`REJECTED`).
### `DELETE /api/v1/pairing/sessions/{id}` (public + poll token) → 204 (TV cancels / refreshes code).

### Devices
| Method | Path | Body / result |
|---|---|---|
| GET | `/api/v1/devices` | `{ items: [{ id, type, name, manufacturer, model, osVersion, appVersion, createdAt, lastSeenAt, online, current }] }` |
| GET | `/api/v1/devices/{id}` | device |
| PATCH | `/api/v1/devices/{id}` | `{ name }` 1–60 chars → device; emits `DEVICE_UPDATED` |
| DELETE | `/api/v1/devices/{id}` | 204; sets `revoked_at`, revokes its sessions (`DEVICE_REMOVED`), closes its sockets with code 4403, emits `DEVICE_REMOVED`. Deleting the **current** device = logout. |
| PUT | `/api/v1/devices/current/push-token` | `{ fcmToken }` — stored for Phase 14 (column `fcm_token` nullable; add now) |

### Entitlements stub
`GET /api/v1/entitlements` → `{ plan: "FREE", entitlements: { max_devices: 2, max_tv_devices: 1, max_saved_links: 50, max_active_downloads: 1, max_queued_downloads: 3, max_storage_destinations: 1, scheduled_downloads: false, bandwidth_controls: false, family_profiles: false, priority_support: false, advanced_playback: false, download_history: false }, source: "default" }`.
`EntitlementsService.getEffective(userId)` returns these from a config file
(`src/modules/entitlements/defaults.ts`) for now. **All limit checks call this service.**

---

## 7. WebSocket gateway (`/ws`)

- `RealtimeGateway` using `@nestjs/platform-ws` (`WsAdapter`). On upgrade: read
  `Authorization` header, validate exactly like `JwtAuthGuard` (signature, revocation,
  tokenVersion, device not revoked). Reject with HTTP 401 before upgrade, or close `4401`.
- Connection registry: in-memory map `deviceId → socket(s)` on this instance + Redis for
  cross-instance fan-out: publish to `rt:user:{userId}`; every instance subscribes with a
  pattern and delivers to local sockets. (Design for multiple instances now even if dev runs one.)
- Presence: on connect `SET presence:device:{id} <instanceId> EX 60`, refreshed on every `PING`;
  on close `DEL`. Emit ephemeral `DEVICE_ONLINE`/`DEVICE_OFFLINE` to the user's other devices.
  `online` in `GET /devices` = key exists. A sweeper isn't needed thanks to the TTL; emit
  OFFLINE lazily when a TTL lapses (keyspace notifications optional; acceptable to only emit
  on clean close and rely on TTL for the list).
- Messages: `PING`→`PONG`; unknown types → `ERROR {code:"UNSUPPORTED_MESSAGE"}`; frames over
  16 KB → close 1009. Max 5 concurrent sockets per device, 20 per user.
- Token expiry during a connection: server closes with `4401` when the access token's `exp`
  passes; client refreshes and reconnects. (Simple, secure, no in-band re-auth needed.)
- Events emitted in this phase: `DEVICE_CONNECTED`, `DEVICE_UPDATED`, `DEVICE_REMOVED`,
  `DEVICE_ONLINE`, `DEVICE_OFFLINE` (envelope from the conventions doc, `seq: null` for now —
  Phase 4 adds persisted `seq`).
- `RealtimePublisher` service: `publishToUser(userId, event, {exceptDeviceId?})`, used by
  other modules. Keep the gateway thin.

---

## 8. Backend tasks (ordered)

1. Prisma model + migration; partial index on `(status, expires_at)`; device columns.
2. `PairingCodeService` (generate, normalize, HMAC), `PairingService` (state machine, all
   transitions in transactions, `FakeClock`-testable), `PairingController`.
3. Expiry: status endpoint treats `expiresAt < now` as EXPIRED (and persists it); a cron every
   10 min marks stale rows EXPIRED and deletes rows older than 7 days.
4. `EntitlementsModule` stub + `LimitsService.assertCanAddDevice(userId, type, tx)`.
5. `DevicesService` + controller; device-limit check also applied in `otp/verify` when a
   *new* device logs in (return `ENTITLEMENT_LIMIT` with a hint to remove a device).
6. `RealtimeModule`: ws adapter, gateway, auth, registry, Redis pub/sub, presence, publisher.
7. Wire events from devices/pairing services.
8. Swagger + OpenAPI export; add `docs/architecture.md` section "Pairing & presence".

### Backend tests
- Unit: code alphabet/normalization, state machine transitions (table-driven), expiry.
- E2E:
  - full flow create → claim → approve → status returns tokens once → second status `CONSUMED` without tokens → TV token works on `/users/me` and shows device type `ANDROID_TV`.
  - wrong poll token → 404; expired code claim → `PAIRING_CODE_INVALID`; claim by second user → `PAIRING_ALREADY_CLAIMED`; approve by a different user → 404.
  - TV cannot call `/pairing/claim` (403).
  - limit: FREE user with 1 TV approving a second → `ENTITLEMENT_LIMIT` (`max_tv_devices`).
  - brute force: 6 wrong codes → locked with `RATE_LIMITED`.
  - device rename, list (`current` flag), delete → that device's token now 401, socket closed 4403.
  - WebSocket: connect without token → rejected; with token → PING/PONG; second device of the same user receives `DEVICE_ONLINE`; other user's sockets receive nothing (isolation).
  - Long-poll returns immediately when approval happens.

---

## 9. Android tasks

### 9.1 `core:realtime`
- `RealtimeClient` (OkHttp WebSocket): `connect()`, `disconnect()`, `events: SharedFlow<RealtimeEvent>`,
  `state: StateFlow<ConnectionState>` (`Disconnected`, `Connecting`, `Connected`, `Backoff(nextAttemptAt)`).
- Lifecycle: connect when `authState == LoggedIn` **and** app process is in foreground
  (`ProcessLifecycleOwner`) — for TV also while downloads are active (Phase 6 will add that);
  disconnect on logout.
- Reconnect with exponential backoff + full jitter (1 s → 60 s cap); reset on success; reconnect
  immediately on network regained (`ConnectivityManager.NetworkCallback`).
- Close codes: `4401` → trigger token refresh then reconnect; `4403` → `SessionManager.logout(DEVICE_REMOVED)`.
- PING every 25 s. kotlinx.serialization polymorphic decoding by `type`; unknown types ignored (forward compatibility).

### 9.2 TV — pairing as the default sign-in
- **TvWelcomeScreen**: left: large QR (ZXing `QRCodeWriter` → `ImageBitmap`, ≥ 280 dp, quiet zone,
  high contrast) and the code in huge monospace `ABCD-2345`; right: 3 numbered steps ("Open
  VideoBridge on your phone → Devices → Connect a TV, or scan this code"); countdown "Code
  refreshes in 4:12"; button "Sign in with phone number instead" (→ Phase 2 keypad flow).
- `TvPairingViewModel`: creates a session, long-polls status (fallback 2 s polling), auto
  refreshes the code 10 s before expiry (cancel old), handles `CLAIMED` (show "Confirm on your
  phone…" with phone-side name), `REJECTED` (message + new code), `APPROVED` (save tokens via
  `EncryptedTokenStore`, set auth state, navigate home). Stops polling when the screen is not
  visible.
- Never display or log the poll token.

### 9.3 Phone — connect a TV
- Entry points: Devices screen "Connect a TV" FAB, and App Link `https://<domain>/pair?c=…` /
  `videobridge://pair?c=…` (manifest intent filters, `autoVerify` for App Links in prod).
- **ConnectTvScreen**: "Scan QR" (Google code scanner `GmsBarcodeScanning` — no camera
  permission needed; if Play services unavailable, fall back to manual) + manual code field
  (8 chars, auto-dash, uppercase, unambiguous alphabet validation).
- **ConfirmTvSheet**: "Connect this TV?" with TV name/model, editable name, **Connect** and
  **Not my TV** buttons. Show limit error nicely: "Your FREE plan allows 1 TV. Remove a TV or
  upgrade." (the upgrade button is a placeholder until Phase 8).
- **DevicesScreen**: list grouped Phones / TVs, online dot (from `GET /devices` + live
  `DEVICE_ONLINE/OFFLINE`), "This device" label, last seen ("2 h ago"), rename dialog, remove
  with confirmation ("The TV will be signed out and stop syncing. Downloads already on it stay
  on its storage."). Updates live on `DEVICE_*` events.
- Store devices in Room (`DeviceEntity`) as the UI source of truth; Room version bump with migration.

### 9.4 TV — device awareness
- Settings → "Signed in as …", "Sign out", and the TV's own name (rename).
- When `DEVICE_REMOVED` for itself or close code 4403 → clear tokens & data → TvWelcomeScreen
  with message "This TV was removed from your account."

### 9.5 Android tests
- `RealtimeClientTest` with MockWebServer WebSocket: connect with header, PONG handling,
  reconnect/backoff with virtual time, 4401 triggers refresh, 4403 triggers logout, unknown event ignored.
- `TvPairingViewModelTest`: code refresh before expiry, APPROVED stores tokens once, REJECTED regenerates.
- `ConnectTvViewModelTest`: code normalization, each error code mapped to a message.
- Room migration test for the new `devices` table.

---

## 10. File structure (new/changed)

```
backend/src/modules/pairing/{pairing.module.ts,pairing.controller.ts,pairing.service.ts,pairing-code.service.ts,dto/*}
backend/src/modules/devices/{devices.controller.ts,devices.service.ts,dto/*}
backend/src/modules/entitlements/{entitlements.module.ts,entitlements.service.ts,limits.service.ts,defaults.ts,entitlements.controller.ts}
backend/src/modules/realtime/{realtime.module.ts,realtime.gateway.ts,ws-auth.ts,connection-registry.ts,realtime.publisher.ts,presence.service.ts,events.ts}
backend/test/{pairing.e2e-spec.ts,devices.e2e-spec.ts,realtime.e2e-spec.ts,helpers/ws-client.ts}
android/core/realtime/…/{RealtimeClient.kt,RealtimeEvent.kt,EventDecoder.kt,Backoff.kt,RealtimeModule.kt}
android/core/database/…/{DeviceEntity.kt,DeviceDao.kt,migrations/Migration1To2.kt}
android/core/data/…/{DevicesRepository.kt,PairingRepository.kt}
android/app-tv/…/pairing/{TvWelcomeScreen.kt,TvPairingViewModel.kt,QrCodeImage.kt}
android/app-phone/…/devices/{DevicesScreen.kt,DevicesViewModel.kt,ConnectTvScreen.kt,ConfirmTvSheet.kt,ConnectTvViewModel.kt}
android/app-phone/src/main/AndroidManifest.xml (pair deep links)
```

---

## 11. Security requirements
- Codes and poll tokens stored only as hashes; tokens handed to the TV once, from Redis, then deleted.
- Explicit confirmation on the phone; the TV can never self-approve.
- Only PHONE devices can claim/approve; only the claiming user can approve/reject.
- Short TTL (5 min), single use, rate-limited claims and creations, brute-force lockout.
- Revoked devices: tokens, refresh and sockets all stop working immediately.
- WebSocket: auth on upgrade, per-user isolation tests, frame size and connection caps.
- Don't trust TV-supplied names for anything but display; sanitize length/control characters.

## 12. Verification commands
```bash
make test
# TV side
P=$(curl -s -XPOST localhost:3000/api/v1/pairing/sessions -H 'content-type: application/json' \
   -d '{"tv":{"installId":"0192bbbb-0000-7000-8000-000000000001","name":"Living Room TV","model":"Emu"}}'); echo $P | jq
# Phone side (AT = phone access token from Phase 2 flow)
curl -s -XPOST localhost:3000/api/v1/pairing/claim -H "authorization: Bearer $AT" -H 'content-type: application/json' -d "{\"code\":\"$(echo $P | jq -r .code)\"}" | jq
curl -s -XPOST localhost:3000/api/v1/pairing/$(echo $P | jq -r .pairingId)/approve -H "authorization: Bearer $AT" | jq
curl -s localhost:3000/api/v1/pairing/sessions/$(echo $P | jq -r .pairingId)/status -H "x-pairing-poll-token: $(echo $P | jq -r .pollToken)" | jq
npx wscat -c ws://localhost:3000/ws -H "Authorization: Bearer $AT"     # then send {"v":1,"type":"PING"}
```

## 13. Acceptance criteria
- [ ] TV signed-out screen shows QR + code with countdown and auto-refresh.
- [ ] Scanning on the phone opens the confirm sheet; Connect signs the TV in within ~2 s (long-poll).
- [ ] Manual code entry works (case/dash insensitive); invalid/expired codes give clear errors.
- [ ] Codes are single-use, short-lived, rate-limited; tokens delivered exactly once.
- [ ] FREE limit (1 TV / 2 devices) enforced via `EntitlementsService`, with a clear phone message.
- [ ] Device list shows phones and TVs with online status updating live; rename and remove work on both ends.
- [ ] Removing a TV signs it out immediately (socket closed 4403, next API call 401) and the TV shows the removal message.
- [ ] WebSocket authenticates on upgrade, isolates users, handles reconnect and token expiry.
- [ ] All tests pass (old and new), OpenAPI regenerated, docs updated.

## 14. Manual test script
1. Fresh TV emulator app → QR shown. Phone emulator → Devices → Connect a TV → type the code → confirm → TV lands on home.
2. Phone Devices list shows the TV "online". Close the TV app → within ~60 s shows offline/last seen.
3. Rename TV on phone → TV settings shows the new name.
4. Pair a second TV (another emulator or clear data) → limit error on FREE.
5. Remove the TV from the phone → TV returns to the pairing screen with the removal message.
6. Real phone camera: scan the QR on the TV screen from ~2 m — must scan easily (size/contrast check).

## 15. Pitfalls
- Some TV emulators/real TVs have wrong clocks: use server `expiresAt` + local monotonic
  elapsed time for countdowns, never device wall clock alone.
- `GmsBarcodeScanning` requires Google Play services; always keep manual entry.
- Long-poll requests must be excluded from aggressive proxy timeouts later (Phase 17 — note it).
- With `WsAdapter`, Nest guards don't run on the upgrade by default — do auth in `handleConnection`
  (or `verifyClient`) explicitly and test it.
