# Phase 03 — Devices & TV Pairing — Completion Report

**Date:** 2026-10-08  **Branch / commits:** none — uncommitted working tree.

**Reduced scope, by the owner's "finish the app as soon as possible":** pairing and device
management are built; the WebSocket gateway and live presence are **not** — they move to Phase 4,
which is the first phase that needs realtime. On MongoDB (ADR-0006), without Redis (ADR-0007).

> **Addendum, same day (owner's changes after trying it)**
> - **Bug fixed:** the TV showed "Can't reach the server" instead of a code. The TV app sent a
>   `type` field with its pairing request and the backend rejected the unknown field (400); the
>   screen mislabelled that as a network problem. The backend now accepts the field, the e2e
>   test sends it like the real app does, and the message no longer blames the network.
> - **Pairing code is now 4 digits** (was 8 characters), by the owner's choice. Codes repeat over
>   time, so the unique index on the code hash was removed; the backend never gives two waiting
>   TVs the same code. With 10,000 possibilities the protection is the 5-minute lifetime and the
>   lock after 5 wrong codes per 10 minutes per account; what a correct guess achieves is signing
>   a stranger's waiting TV in to the guesser's own account, not access to anyone's data.
> - **Phone:** Connect a TV, My devices and Sign out moved from the middle of Home into the
>   top-right menu; Home is left for videos.
> - **Seen on the real TV:** the QR code and a 4-digit code with countdown; backend log shows the
>   session created (201) and polled every ~2 s. Seen on the real phone: the menu with the three
>   entries. **Still not seen:** a phone actually connecting the TV.
> - Tests now: backend 133 unit / pairing e2e 11 of 11; Android 68.

## 1. Implementation summary
A signed-out TV now opens on a "Connect with your phone" screen showing a QR code and an
8-character code that renews itself before it expires. On the phone, **Connect a TV** scans the
QR (or takes the typed code), shows "Connect this TV?" with the TV's name, and on Connect the TV
signs itself in within about two seconds — nothing is typed on the TV. Signing in with number
and password remains available on the TV as a fallback. The phone has a **My devices** screen
to rename or remove phones and TVs; a removed device is signed out immediately. Also in this
round: the TV sign-in form was redesigned (two panes, nothing moves when the keyboard opens,
Next key, TV-style keyboard), the TV opens on sign-in rather than create-account, and signing
out on the TV now asks for confirmation.

## 2. Files created / modified
| Path | Change | Why |
|---|---|---|
| `backend/src/modules/pairing/*` | created | pairing state machine, endpoints |
| `backend/src/modules/devices/devices.controller.ts`, `devices.service.ts` | created / modified | list, rename, remove |
| `backend/src/modules/entitlements/entitlements.module.ts` | created | FREE-plan stub used by limit checks |
| `backend/src/common/utils/crypto.ts` | modified | AES-GCM helpers, key derivation |
| `backend/prisma/schema.prisma`, `prisma/indexes.ts` | modified | `pairing_sessions` + TTL index |
| `backend/test/pairing.e2e-spec.ts` | created | full flow |
| `android/core/network/PairingApi.kt`, `core/data/devices/*`, `core/data/ApiCall.kt` | created | APIs and repositories |
| `android/feature/auth/{TvPairing,ConnectTv,Devices}ViewModel.kt` | created | screen logic |
| `android/app-tv/pairing/*`, `app-tv/auth/TvAuthScreen.kt`, `app-tv/home/TvHomeScreen.kt` | created / modified | pairing screen, redesigned sign-in, sign-out confirmation |
| `android/core/tv-designsystem/TvKeyboard.kt` | modified | TV-style look, Next/Done, remote number keys |
| `android/app-phone/devices/*`, `home/HomeScreen.kt`, `MainActivity.kt` | created / modified | Connect a TV, My devices |

## 3. Database changes
- New collection `pairing_sessions` (unique `code_hash`; TTL index removes rows a day after expiry).
- No migration files (MongoDB); applied with `npm run prisma:migrate` to both databases.

## 4. API changes
| Method | Path | Auth | Change |
|---|---|---|---|
| POST | `/api/v1/pairing/sessions` | public (TV) | new |
| GET | `/api/v1/pairing/sessions/:id/status` | poll token header | new — sign-in returned exactly once |
| POST | `/api/v1/pairing/claim` | user, phone only | new |
| POST | `/api/v1/pairing/:id/approve`, `/reject` | claiming user | new |
| GET / PATCH / DELETE | `/api/v1/devices`, `/devices/:id` | user | new |
| GET | `/api/v1/entitlements` | user | new (stub: 2 devices, 1 TV) |
- New error codes: `PAIRING_CODE_INVALID`, `PAIRING_ALREADY_CLAIMED`, `PAIRING_EXPIRED`.
- WebSocket events: none (deferred).
- `docs/api/openapi.json` regenerated: yes.

## 5. Android changes
- Phone: Connect a TV (Google code scanner, no camera permission; typed code always works), My devices.
- TV: pairing screen is the default when signed out; redesigned sign-in; sign-out confirmation.
- New dependencies: ZXing core 3.5.4 (TV renders the QR), play-services-code-scanner 16.1.0 (phone).
- No new permissions. Room schema unchanged.

## 6. Tests and build results
```
backend: lint ✔  format:check ✔  typecheck ✔  build ✔
         npm test          → 134 passed, 0 failed
         npm run test:e2e  → 50 passed, 0 failed (Atlas test database)
android: ./gradlew spotlessCheck detekt lintDevDebug testDevDebugUnitTest
                   :app-phone:assembleDevDebug :app-tv:assembleDevDebug → BUILD SUCCESSFUL
         unit tests → 68 passed, 0 failed;  lint 0 errors
```
On real devices: both apps installed. Phone: My devices loaded from the backend (200) and the
Connect a TV screen opened (the owner navigated there). TV: still signed in, home shows the logo
and name. **Pairing itself was not run on the real TV**, because that needs the TV signed out and
the owner was using it; the same goes for the redesigned TV sign-in form.

## 7. Acceptance criteria
- [x] Signed-out TV shows QR + code with countdown and auto-refresh (view-model and UI tests). *Not seen on the real TV.*
- [ ] Scanning on the phone opens the confirm step and Connect signs the TV in within ~2 s — **backend flow proven by e2e; not run phone-to-TV on devices.** Polling every 2 s, no long-poll.
- [x] Manual code entry, case/dash insensitive; clear errors for invalid/expired codes.
- [x] Codes single-use, 5-minute, rate-limited (5 wrong codes lock for 10 minutes); tokens delivered exactly once.
- [x] FREE limit (1 TV / 2 devices) enforced through `EntitlementsService`; re-pairing the same TV is allowed.
- [x] Device list, rename, remove. *No live online status.*
- [x] Removing a TV makes its tokens and refresh fail on the next request. *The TV then shows the sign-in screen with "You were signed out", not a dedicated "removed" message; no socket to close.*
- [ ] **WebSocket with auth, isolation, reconnect — not built (moved to Phase 4).**
- [x] All tests pass; OpenAPI regenerated.

## 8. Manual testing checklist
1. TV: Sign out (now asks to confirm) → the QR/code screen appears.
2. Phone: Connect a TV → Scan QR code (from about 2 m) → "Connect this TV?" → Connect → the TV shows "Hi, <name>".
3. Repeat with the typed code instead of scanning.
4. Phone: My devices → rename the TV; remove the TV → the TV returns to the QR screen on its next request.
5. TV: "Sign in with mobile number instead" → try the keyboard: Next, Down past the last row, Up past the first row, Back.

## 9. Security notes
- Codes are stored as HMACs and poll tokens as SHA-256; the TV's sign-in waits AES-256-GCM
  encrypted in the pairing document and is deleted on pickup. The QR holds only the code.
- Only a phone can claim; only the claiming user can approve; a second claimer gets 409.
- Every state change is a conditional update on the current status (no double approval or pickup).
- The device limit is checked at pairing only, not at password sign-in, and count-then-create is
  not transactional: two approvals racing for the last slot could both pass. Revisit in Phase 8.

## 10. Known issues / deferred items
| Issue | Impact | Planned phase |
|---|---|---|
| No WebSocket gateway, presence or `DEVICE_*` events | no live online dot; lists refresh on open | 4 |
| No long-poll (2 s polling) | up to 2 s delay after Connect | 11 |
| Devices not stored in Room | My devices needs the network | 9 |
| No `videobridge://pair` deep link / App Link | QR must be scanned from inside the app | 9 |
| Device limit not applied at password sign-in | a third device can sign in with the password | 8 |
| TV doesn't say "this TV was removed" specifically | generic signed-out message | 10 |
| Cold start on the Android 9 TV after each install is slow (10–75 s of blank screen) | debug build; expected to improve with a release build and baseline profile | 10 / 18 |
| Push-token column and endpoint not added | none until notifications | 14 |

## 11. Library versions chosen / changed
ZXing core 3.5.4; play-services-code-scanner 16.1.0.

## 12. Next-phase recommendations
- Phase 4 (saved links) must bring the realtime channel with it. Without Redis the fan-out is
  in-process (ADR-0007); `seq` ordering needs a transaction on the user document (ADR-0006).
- Reuse `TvKeyboard` for TV search.
