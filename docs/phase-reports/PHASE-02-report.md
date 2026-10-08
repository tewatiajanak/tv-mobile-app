# Phase 02 — Authentication — Completion Report

**Date:** 2026-10-08  **Branch / commits:** none — uncommitted working tree (owner handles git later).

**Built to the owner's changed brief, not the phase file:** mobile number + password instead
of OTP (ADR-0008), on MongoDB (ADR-0006) and without Redis (ADR-0007).

> **Addendum, same day**
> - App renamed to **Dekho** with the owner's logo: launcher icon (adaptive), in-app logo on the
>   sign-in screens, TV launcher banner. Seen on the real TV's apps row with name and banner.
> - **TV sign-in now uses an in-app D-pad keyboard** (`TvKeyboard`) instead of the system one:
>   Down past the last row closes it and focuses the next field (or the buttons after the last
>   field); Up past the first row closes it and returns to the field, from where Up reaches the
>   field above; Back and Done close it. Letters, digits, symbols, shift, space, delete; a number
>   pad for the mobile number. Covered by 10 key-press tests (Robolectric). **Not confirmed on
>   the real TV:** the owner was using the TV at the time, so remote keys could not be driven.
> - The owner signed in on the TV themselves (home showed "Hi, Janak"), so TV sign-in works end
>   to end with the previous build. A key press sent over adb during testing then most likely
>   activated "Sign out" on that TV.
> - Android now: 56 unit tests, 0 failed; lint 0 errors. Backend unchanged.

## 1. Implementation summary
A user creates an account on the phone with name, mobile number and password (any password, one
character is enough), or signs in with number and password. The password field has an eye icon
to show or hide it. After signing in the app shows "Hi, <name>" at the top and does not ask
again: killing and reopening the app goes straight to the home screen. The TV has the same
form using the TV keyboard. The backend stores only a scrypt hash of the password, issues
15-minute access tokens and rotating refresh tokens, detects refresh-token reuse, and supports
logout, logout-everywhere and per-device session removal.

## 2. Files created / modified
| Path | Change | Why |
|---|---|---|
| `backend/prisma/schema.prisma`, `prisma/indexes.ts` | modified / created | users, devices, sessions, rate_limits; TTL index |
| `backend/src/common/utils/{crypto,phone,client-ip}.ts` | created | hashing, phone normalisation/masking, hashed IP |
| `backend/src/common/rate-limit/*` | created | MongoDB fixed-window limiter |
| `backend/src/common/decorators/*` | created | `@Public()`, `@CurrentPrincipal()` |
| `backend/src/modules/auth/*`, `users/*`, `devices/*` | created | register, login, refresh, sessions, guard, profile |
| `backend/src/config/*`, `.env.example` | modified | JWT/refresh/IP-salt settings |
| `backend/test/auth.e2e-spec.ts`, `helpers/db.ts` | created | full flow against the Atlas test database |
| `android/core/datastore/*` | modified | install id, Tink-encrypted session store |
| `android/core/network/*` | modified | auth API, auth interceptor, single-flight token authenticator |
| `android/core/data/auth/*` | created | `SessionManager`, `AuthRepository`, device info |
| `android/feature/auth/*` | created | shared `AuthViewModel`, `SessionViewModel` |
| `android/app-phone`, `android/app-tv` | modified | sign-in and home screens; Phase 1 startup screens removed |
| `docs/decisions/ADR-0008-password-login.md` | created | the decision and its risks |

## 3. Database changes
- No migration files (MongoDB). `npm run prisma:migrate` = `prisma db push` + `prisma/indexes.ts`.
- New collections: `users` (unique `phone_e164`), `devices`, `sessions` (unique
  `refresh_token_hash`), `rate_limits` (TTL on `expires_at`). Applied to `videobridge` and
  `videobridge_test`.
- Not created: `otp_requests`.

## 4. API changes
| Method | Path | Auth | Change |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | new — `{name, phone, password, device}` |
| POST | `/api/v1/auth/login` | public | new — `{phone, password, device}` |
| POST | `/api/v1/auth/refresh` | public | new |
| POST | `/api/v1/auth/logout`, `/auth/logout-all` | user | new |
| GET / DELETE | `/api/v1/auth/sessions`, `/auth/sessions/:id` | user | new |
| GET / PATCH | `/api/v1/users/me` | user | new |
- Every other route now requires a token unless marked `@Public()` (health is public).
- New error codes: `INVALID_CREDENTIALS`, `PHONE_ALREADY_REGISTERED`, `REFRESH_INVALID`,
  `SESSION_REVOKED`, `REFRESH_RACE`, `USER_SUSPENDED`.
- Not built: `/auth/otp/request`, `/auth/otp/verify`.
- `docs/api/openapi.json` regenerated: yes.

## 5. Android changes
- Phone: `AuthScreen` (create account / sign in, eye icon), `HomeScreen` (name in the top bar, sign out).
- TV: `TvAuthScreen` (system keyboard, labelled Show/Hide button), `TvHomeScreen`.
- New module `feature:auth`; new dependency Tink 1.23.0.
- No new permissions. Room schema unchanged (version 1).

## 6. Tests and build results
```
backend: lint ✔  format:check ✔  typecheck ✔  build ✔
         npm test          → 119 passed, 0 failed (11 suites)
         npm run test:e2e  → 40 passed, 0 failed (Atlas test database, no Redis)
android: ./gradlew spotlessCheck detekt lintDevDebug testDevDebugUnitTest
                   :app-phone:assembleDevDebug :app-tv:assembleDevDebug → BUILD SUCCESSFUL
         unit tests → 49 passed, 0 failed;  lint 0 errors
```
On real devices (OnePlus CPH2619 / Android 16, Xiaomi MiTV-AXSO2 / Android 9):
- Phone: the owner created an account in the app; backend answered 201; home shows "Hi, Janak".
- Phone: force-stop and relaunch → still signed in, home in about 1.5 s.
- TV: the create-account form appears and the TV keyboard opens on the first field.

## 7. Acceptance criteria (as changed by ADR-0008)
- [x] Name, number and password are all mandatory (backend validation + disabled button).
- [x] A one-character password works; only a hash is stored (e2e reads the stored value).
- [x] Eye icon shows/hides the password (UI tests; phone screen seen on device).
- [x] Name shown at the top after sign-in (seen on device).
- [x] App does not ask to sign in again (seen on device after force-stop).
- [x] Access tokens expire after 15 min; refresh rotates; reuse revokes the session.
- [x] Logout, logout-all and session removal take effect on the next request.
- [x] Protected endpoints reject missing/invalid tokens with the envelope.
- [x] One active session per device.
- [x] Logs carry no passwords or tokens, phones masked (redaction tests; backend log checked after a real sign-up).
- [ ] **TV sign-in completed end to end — not done.** Only the form and keyboard were seen on the TV.
- [ ] **Silent token refresh on a device — not observed.** Covered by `TokenAuthenticatorTest` only.
- [ ] **Sign-out, wrong-password and offline messages on a device — not checked.** Unit/UI tests only.
- n/a OTP expiry, resend cooldown, SMS providers, test numbers.

## 8. Manual testing checklist
1. Phone: Sign out → "I already have an account" → number + password → home. Try a wrong password → "Wrong mobile number or password."
2. TV: choose "I already have an account", sign in with the same number and password using the remote → "Hi, <name>" at the top.
3. TV: check the Show/Hide button can be reached with the D-pad and works.
4. Stop the backend, try to sign in → "Can't reach the server…". Start it again → works.
5. Leave the phone app closed for over 15 minutes, reopen → still signed in (silent refresh).

## 9. Security notes
See ADR-0008 for the full list. In short: numbers are unverified, weak passwords are allowed,
there is no password reset, and ten wrong guesses lock a number's sign-in for an hour. Each
authenticated request does two database reads (session and user) so revocation is immediate.
The Atlas password that was pasted into chat is still in use.

## 10. Known issues / deferred items
| Issue | Impact | Planned phase |
|---|---|---|
| TV opens on "Create account"; "Sign in" would be the better default there | one extra click on TV | next change |
| No cleanup job for expired sessions | rows accumulate; expired ones are already rejected | 11 |
| No device-limit check at sign-in | none until entitlements exist | 3 |
| Sessions screen on the phone (list / remove devices) not built; API exists | can't see other devices in the app | 3 |
| `X-Device-Id` header not sent by the apps | backend doesn't need it yet | 3 |
| No password change or reset | forgotten password = locked out | 13 or sooner |
| Refresh-race path (409) untested end to end (grace set to 0 in e2e) | low | 11 |
| CI workflow has never run | may need fixes on first push | first push |

## 11. Library versions chosen / changed
Backend: jsonwebtoken 9.0.3, libphonenumber-js 1.13.15. Android: Tink 1.23.0,
lifecycle-viewmodel-ktx 2.11.0. Lint: ktlint/detekt line length raised to 140; detekt ignores
cyclomatic complexity of `@Composable` functions.

## 12. Next-phase recommendations
- Phase 3 (devices and TV pairing) should make pairing the main TV sign-in, so typing a
  password with a remote becomes the fallback.
- Translate Phase 3's `pairing_sessions`, Redis token hand-off and pub/sub long-poll through
  ADR-0006 and ADR-0007 in its plan.
- In MongoDB, write nullable fields that are filtered on (`revokedAt`) as explicit `null`.
