# Phase 13 — Admin Dashboard

> **Implement this phase only. Do not start Phase 14. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-13-admin-dashboard.md
and the latest phase report. We are implementing Phase 13 (Admin Dashboard) only.
Inspect the backend modules (users, devices, subscriptions, billing, entitlements, downloads) first.
Give me a numbered plan: admin identity + auth (password + TOTP, cookie sessions, CSRF), RBAC
permission matrix, audit log (append-only), admin APIs per area, feature flags + system config +
client /config endpoint, reports/exports, the admin-web React app pages, and tests (permission
matrix, audit coverage, Playwright). Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 13 step by step: admin auth → RBAC → audit log → admin APIs area by area (each
with permission + audit tests) → feature flags/config → reports → admin-web pages → Playwright tests.
Build, test and commit after each step. Never expose OTPs, tokens, secrets or hashes.
```

**Prompt C — verify & report**
```
Run all tests including the generated permission matrix and audit-coverage test, and the Playwright
suite. Demonstrate: bootstrap the first admin, enroll TOTP, look up a user, grant a 30-day PRO
override, revoke a device, toggle a feature flag that the apps read, export a CSV, and view the
audit trail of all of it. Tick acceptance criteria, write docs/phase-reports/PHASE-13-report.md,
update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run related + full tests, commit.
```

---

## 1. Goal

A secure web dashboard for operating VideoBridge: users, devices, subscriptions, plans and
entitlements, payments, download statistics and failures, reports, feature flags and system
configuration — with **role-based access**, **2FA**, and an **append-only audit log** of every
admin action. Authentication secrets are never exposed.

## 2. Prerequisites
Phases 2–12 (all domain data exists). Phase 8 Manual provider + user overrides.

## 3. Scope
**In:** admin identities and auth, RBAC, audit log, admin REST APIs, feature flags, system config,
client config endpoint, reports/exports, `admin-web` app, tests.
**Out:** customer support ticketing, marketing tools, BI warehouse.

---

## 4. Admin identity & authentication

- Separate tables (admins are **not** app users):
  `admin_users(id, email citext unique, name, password_hash, totp_secret_enc, totp_enabled, role, status ACTIVE|DISABLED, failed_attempts, locked_until, last_login_at, password_changed_at, created_at, updated_at)`
  `admin_sessions(id, admin_id, token_hash, csrf_token_hash, ip_hash, user_agent, created_at, last_seen_at, expires_at, revoked_at)`.
- Password: Argon2id (memory ≥ 64 MB, t=3), min 12 chars, checked against a common-password list.
- **TOTP 2FA mandatory** (RFC 6238, 30 s, ±1 step); secret encrypted with `ADMIN_TOTP_ENC_KEY`;
  enrollment on first login (QR + manual key); 10 one-time recovery codes (hashed).
- Login: `POST /api/v1/admin/auth/login {email, password}` → `{ mfaRequired: true, mfaToken }` →
  `POST /api/v1/admin/auth/mfa {mfaToken, code}` → sets cookie `vb_admin` (opaque token,
  `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/admin`), returns CSRF token.
  Idle timeout 30 min, absolute 8 h. Lockout after 5 failures for 15 min; rate limits per IP/email.
- CSRF: every state-changing admin request needs header `X-CSRF-Token` matching the session.
- Optional `ADMIN_IP_ALLOWLIST` (CIDRs). Admin API CORS allows only `ADMIN_WEB_ORIGIN`.
- Bootstrap: `npm run admin:create -- --email … --role SUPER_ADMIN` (interactive password prompt,
  forces TOTP enrollment at first login). No default admin, no seeded credentials.
- App JWTs are rejected on `/admin/*`; admin cookies are rejected on app routes (test both).

## 5. RBAC

Roles: `SUPER_ADMIN`, `SUPPORT`, `BILLING`, `ANALYST` (read-only). Permissions are strings;
`@RequirePermission('users.suspend')` on every admin handler (a test fails if a handler lacks it).

| Permission | SUPER_ADMIN | SUPPORT | BILLING | ANALYST |
|---|---|---|---|---|
| `dashboard.read` | ✔ | ✔ | ✔ | ✔ |
| `users.read` (masked PII) | ✔ | ✔ | ✔ | ✔ |
| `users.pii.reveal` (full phone; requires reason) | ✔ | ✔ | ✔ | |
| `users.content.read` (video titles/URLs; requires reason) | ✔ | | | |
| `users.suspend`, `users.sessions.revoke`, `devices.revoke` | ✔ | ✔ | | |
| `users.delete` | ✔ | | | |
| `entitlements.override` | ✔ | ✔ | ✔ | |
| `subscriptions.read` / `payments.read` | ✔ | ✔ | ✔ | ✔ |
| `subscriptions.manage` (manual grant/extend/cancel, provider refresh) | ✔ | | ✔ | |
| `plans.manage` | ✔ | | ✔ | |
| `downloads.stats.read` | ✔ | ✔ | ✔ | ✔ |
| `reports.export` (PII exports) | ✔ | | ✔ | |
| `flags.manage`, `config.manage` | ✔ | | | |
| `admins.manage` | ✔ | | | |
| `audit.read` | ✔ | | | ✔ |

## 6. Audit log

- `audit_logs(id uuid, occurred_at, actor_type ADMIN|SYSTEM|USER, actor_id, action, target_type,
  target_id, reason, ip_hash, user_agent, request_id, before jsonb, after jsonb, prev_hash, hash)`.
- **Append-only:** DB trigger rejects `UPDATE`/`DELETE`; the app DB role has only `INSERT, SELECT`
  on it. Hash chain (`hash = sha256(prev_hash || canonical_json(row))`) with a verify script.
- Written in the same transaction as the change. `before/after` are **sanitized** (no hashes,
  tokens, secrets; phones masked).
- Every admin mutation is audited; also: admin login success/failure, MFA enrollment, PII reveal
  (with reason), exports, and security events from Phase 2 (reuse detection) if desired.
- Test: enumerate all admin routes with non-GET methods and assert each writes an audit row.

## 7. Admin API (`/api/v1/admin/*`)

| Area | Endpoints (examples) |
|---|---|
| Dashboard | `GET /dashboard/summary` (users total/new 7 d, devices online now, videos saved, downloads started/completed/failed 24 h, failure rate, active subs by plan, MRR estimate, payment failures 7 d); `GET /dashboard/timeseries?metric=&range=` |
| Users | `GET /users?query=` (exact phone, user id, device id), `GET /users/{id}` (profile, status, devices, sessions, subscription, entitlements, usage counts, recent activity), `POST /users/{id}/reveal-phone {reason}`, `POST /users/{id}/suspend {reason}`, `POST /users/{id}/unsuspend`, `POST /users/{id}/logout-all`, `DELETE /users/{id} {reason}` (same pipeline as account deletion), `GET /users/{id}/videos?reason=` (content read, audited) |
| Entitlements | `POST /users/{id}/entitlements {key, value, expiresAt, reason}`, `DELETE /users/{id}/entitlements/{overrideId}` |
| Devices | `GET /devices?query=`, `POST /devices/{id}/revoke {reason}` |
| Subscriptions | `GET /subscriptions?status=&plan=&provider=`, `GET /subscriptions/{id}` (+ events timeline), `POST /subscriptions/grant {userId, planCode, days, reason}` (Manual), `POST /subscriptions/{id}/extend`, `POST /subscriptions/{id}/cancel` (Manual only), `POST /subscriptions/{id}/refresh` (re-fetch from Play) |
| Plans | `GET /plans`, `PATCH /plans/{id}` (name, description, active), `PUT /plans/{id}/entitlements` (validated by registry), `PUT /plans/{id}/prices` (warn: Play prices must match Play Console). Plan changes bump a global `ent:version` so all entitlement caches refresh; emit `ENTITLEMENTS_CHANGED` lazily on next fetch |
| Payments | `GET /payments?status=&from=&to=`, `GET /invoices?…` |
| Downloads | `GET /downloads/stats?range=` (counts by state, success rate, avg speed, bytes), `GET /downloads/failures?groupBy=code|domain|deviceModel|appVersion` (from `download_events` + jobs + devices) |
| Reports | `POST /reports {type: SIGNUPS|SUBSCRIPTIONS|PAYMENTS|DOWNLOADS, from, to}` → async job (BullMQ) → `GET /reports/{id}` → signed download URL valid 15 min; files auto-deleted after 24 h |
| Flags | `GET/POST/PATCH/DELETE /flags` |
| Config | `GET /config`, `PUT /config/{key}` |
| Admins | `GET/POST/PATCH /admins`, `POST /admins/{id}/reset-mfa`, `POST /admins/{id}/disable` |
| Audit | `GET /audit?actor=&action=&target=&from=&to=`, `GET /audit/verify` |

All list endpoints: cursor pagination, server-side filtering, max 100 per page. PII masked by default.

## 8. Feature flags & system configuration

- `feature_flags(key pk, description, enabled, rollout_percent 0–100, rules jsonb, updated_by, updated_at)`;
  rules: `platforms` (`android-phone`/`android-tv`), `minAppVersion`, `planCodes`, `userIds` allowlist.
  Bucketing: `murmur3(userId + ":" + key) % 100 < rollout_percent` (deterministic).
- `system_config(key pk, value jsonb, version, updated_by, updated_at)` validated by a schema
  registry (e.g. `otp.limits`, `inspection.platformDenylist`, `maintenance.banner`,
  `app.minSupportedVersion.phone/tv`, `support.contacts`, `downloads.defaultReserveBytes`).
  Cached in Redis, invalidated via pub/sub. **No secrets** in system config (validation rejects keys like `*secret*`, `*token*`, `*password*`).
- Client endpoint `GET /api/v1/config` (auth optional): evaluated flags for the caller, min
  supported version, maintenance banner, support contacts. Android `RemoteConfigRepository`
  (cached, refreshed on start/foreground, `CONFIG_CHANGED` ephemeral event optional);
  **force-update screen** when the app version < min supported; maintenance banner component.

## 9. `admin-web` (React + Vite + TypeScript)

- Stack: React, TypeScript strict, Vite, TanStack Router + Query, Tailwind + shadcn/ui, Zod for forms,
  recharts for charts. Generated API client from `docs/api/openapi.json` (`openapi-typescript`).
- Pages: Login → MFA (enroll/verify) → Dashboard; Users (search, detail tabs: Overview, Devices,
  Sessions, Subscription, Entitlements, Activity); Devices; Subscriptions (list, detail timeline);
  Plans & entitlements editor (diff preview before save); Payments & invoices; Downloads stats &
  failures (charts + tables); Reports; Feature flags; System config (JSON editor with schema
  validation); Admins; Audit log.
- Every destructive action: confirmation dialog + **required reason** field. Buttons hidden/disabled
  by permission (server still enforces).
- Security headers on the served app: strict CSP (no inline scripts), `frame-ancestors 'none'`,
  `Referrer-Policy: no-referrer`. No tokens in localStorage (cookie session only).
- Session timeout warning at 25 min idle.

## 10. Tests
- Backend: login + lockout + MFA (valid/invalid/replayed code, recovery code), CSRF required,
  cookie flags, IP allowlist, app-token/admin-cookie separation.
- **Generated permission matrix:** every admin route × every role → expected 2xx/403.
- **Audit coverage** test (§6) + audit immutability (UPDATE/DELETE rejected) + hash-chain verify.
- PII masking in responses; content read requires reason; reveal audited.
- Overrides/grants reflect in `/entitlements` immediately; plan edit bumps cache version.
- Flags: bucketing determinism, rules evaluation; `/config` for each platform/version; force-update threshold.
- Reports: async generation, signed URL expiry, file cleanup, permission.
- admin-web: unit tests for key components; **Playwright** e2e: login+MFA, user lookup, grant
  override, revoke device, toggle flag, export report, audit view.
- Android: `RemoteConfigRepository` + force-update + maintenance banner tests.

## 11. File structure (new/changed)
```
backend/src/modules/admin/{admin.module.ts,auth/*,rbac/{permissions.ts,roles.ts,require-permission.decorator.ts,admin.guard.ts},users/*,devices/*,subscriptions/*,plans/*,payments/*,downloads-stats/*,reports/*,flags/*,config/*,admins/*,audit/*}
backend/src/modules/audit/{audit.service.ts,audit.verify.ts}
backend/src/modules/feature-flags/{flags.service.ts,bucketing.ts}  backend/src/modules/config/{system-config.service.ts,registry.ts,client-config.controller.ts}
backend/scripts/admin-create.ts
backend/prisma/migrations/…_admin (admin_users, admin_sessions, audit_logs + trigger, feature_flags, system_config, report_jobs)
admin-web/{package.json,vite.config.ts,src/**,tests/e2e/**}
android/core/data/…/{RemoteConfigRepository.kt}  android/app-*/…/{ForceUpdateScreen.kt,MaintenanceBanner.kt}
.github/workflows/admin.yml
```

## 12. Acceptance criteria
- [ ] First admin bootstrapped via CLI; TOTP mandatory; lockout and session timeouts work.
- [ ] RBAC enforced server-side for every route (matrix test green); UI respects permissions.
- [ ] Every admin mutation and sensitive read is in the append-only, hash-chained audit log.
- [ ] User, device, subscription, plan/entitlement, payment, download-stats, report, flag, config and admin management all functional.
- [ ] No OTPs, tokens, secrets, password/token hashes or TOTP secrets ever returned or logged.
- [ ] Flags and config reach apps via `/config`; force-update and maintenance banner work on phone and TV.
- [ ] CSV exports async, permissioned, time-limited.
- [ ] All backend, admin-web and Playwright tests pass.

## 13. Pitfalls
- Don't reuse app auth for admins — different threat model.
- `SameSite=Strict` cookies + separate admin origin simplify CSRF, but keep the header token anyway.
- Aggregation queries on `download_events` grow quickly — add indexes / daily rollup table (`download_stats_daily`) if queries exceed ~200 ms.
- Editing Play prices in the dashboard does **not** change Play Console — make that impossible to miss in the UI.
