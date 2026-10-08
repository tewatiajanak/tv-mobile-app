# Phase 15 — Security Hardening

> **Implement this phase only. Do not start Phase 16. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-15-security-hardening.md
and all previous phase reports' "Security notes". We are implementing Phase 15 (Security Hardening) only.
First run the automated scanners listed in the phase file and inventory the attack surface
(every REST route, WS message type, exported Android component, deep link, webhook, admin route,
secret and data store). Then give me a numbered audit plan per area with the checks you'll perform
and the tests you'll add. Do not change code yet.
```

**Prompt B — audit & fix**
```
Go. Work area by area. For each check: record the result in docs/security-audit.md with evidence
(file:line, test name or command output). Fix every Critical and High finding now with a regression
test; fix Medium findings when the fix is small, otherwise document them with a plan. Commit per area.
```

**Prompt C — verify & report**
```
Re-run all scanners and the full test suite including the new security tests. Make sure
docs/security-audit.md is complete (threat model, checklist with evidence, findings table with
status). Write docs/phase-reports/PHASE-15-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste finding or failing test>. Explain the vulnerability and impact in two sentences, fix it,
add a regression test, run the suite, commit.
```

---

## 1. Goal

A systematic security audit of the entire system with Critical/High issues fixed, automated
security checks in CI, and a complete `docs/security-audit.md` that a reviewer could trust.

## 2. Scope
Authentication, OTP, sessions, pairing, REST, WebSocket, URL validation/SSRF, storage permissions,
database authorization, subscriptions/billing, admin roles, rate limiting, input validation,
logging, secrets, Android app hardening, dependencies, infrastructure config (as far as it exists),
privacy (India DPDP Act basics).

---

## 3. Automated tooling (add to CI as `security.yml`; fail on High/Critical)

| Tool | Target |
|---|---|
| `gitleaks` (full history) | committed secrets |
| `osv-scanner` / `npm audit --omit=dev` | backend + admin-web dependencies |
| OWASP Dependency-Check or `osv-scanner` on Gradle lockfiles (enable Gradle dependency locking) | Android dependencies |
| `semgrep` (`p/owasp-top-ten`, `p/nodejs`, `p/typescript`, `p/kotlin`, custom rules below) | code |
| `trivy image` | backend Docker image |
| Android Lint security checks + `MobSF` static scan (manual, report attached) | APKs |
| OWASP ZAP baseline/API scan against a local/staging backend with the OpenAPI spec | DAST |

Custom semgrep rules: outbound HTTP outside `common/net/`; Prisma queries on user tables without
`userId`; `console.log`; logging of variables named `*token*|*otp*|*code*|*secret*`; `$queryRawUnsafe`;
`Math.random` for security values.

---

## 4. Audit checklist (each item → evidence + test)

### 4.1 Authentication & OTP
- OTP entropy (`crypto.randomInt`), HMAC storage, timing-safe compare, attempts incremented under lock.
- Expiry, single use, resend invalidation, escalating cooldowns, per-phone/IP/install limits.
- No user enumeration (response shape + timing measured: p50 difference < 20 ms).
- **SMS pumping / toll fraud:** country allowlist (default `IN` only, configurable), daily global
  SMS budget with alerting and automatic circuit-breaker, optional **Play Integrity** token check on
  `otp/request` (verdict must be `MEETS_DEVICE_INTEGRITY` + app recognized) — implement behind a flag.
- Test numbers impossible in production (boot test).
### 4.2 Tokens & sessions
- JWT: algorithm pinned (reject `none`/HS↔RS confusion), `iss/aud/typ` checked, short TTL,
  `kid` header + **key ring** to allow secret rotation without logging everyone out (implement now).
- Refresh: rotation, reuse detection, hashing, sliding expiry caps, revocation propagation (≤ 1 request).
- Device revocation kills tokens, refresh and sockets. Logout-all bumps token version.
### 4.3 Pairing
- Code entropy/TTL/single-use, brute-force lockouts, poll-token secrecy, tokens delivered once,
  claim only by PHONE, approve only by claimer, explicit confirmation even via deep link
  (deep link never auto-approves), QR contains no secrets.
### 4.4 Authorization (IDOR / tenancy)
- **Generated cross-tenant suite:** for every route with an id parameter (from the OpenAPI spec),
  create the resource as user A, call as user B → expect 404 (not 403, to avoid existence leaks).
  Same for WS: user B never receives A's events; commands only to own devices.
- DTO whitelisting (`forbidNonWhitelisted`) prevents mass assignment of `userId`, `status`, `version`.
- DB: application role without superuser/DDL privileges at runtime (migrations use a separate role);
  consider Postgres RLS as defense-in-depth (ADR: adopt or not, with reasons).
### 4.5 REST & WebSocket
- Security headers (helmet), CORS allowlist, body limits, JSON depth limits, pagination caps,
  expensive endpoints (search, preview, reports) rate-limited.
- WS: auth on upgrade, browser `Origin` rejected (mobile clients send none; admin never uses WS),
  frame size, message rate, connection caps, backpressure, close codes.
- Error envelope never leaks stack/SQL/hostnames (fuzz a few endpoints with malformed input).
- ReDoS review of all regexes (backend URL/phone parsing, Android share-text regex) with long adversarial inputs.
### 4.6 URL validation & SSRF
- Re-run and extend the Phase 5 SSRF suite with a public bypass list (e.g. PayloadsAllTheThings
  SSRF section): IPv6 forms, DNS rebinding, redirects, `@` and `\` parser confusion, unicode dots,
  trailing dots, enclosed alphanumerics, mixed-encoding hosts.
- Confirm only `SafeHttpClient` makes user-URL requests (semgrep rule).
- Body caps and timeouts enforced; metadata service IPs blocked; inspection workers have no access
  to internal networks in production (egress firewall noted for Phase 17).
### 4.7 Android app
- Exported components: only launcher, share receiver, deep-link activity, FCM service; each
  validates input (share text capped at 100 KB; URLs re-validated; deep link params whitelisted).
- `PendingIntent` all `FLAG_IMMUTABLE`; no implicit internal intents.
- No WebView (or, if any, JS disabled & no file access).
- Network security config: cleartext only in `dev`; **certificate pinning decision** in an ADR
  (recommended: no leaf pinning; optional pin of CA intermediates with backups and expiry monitoring).
- Token storage (Tink + Keystore), `allowBackup`/data extraction rules exclude secrets and DB.
- R8 enabled for release; no secrets in `BuildConfig`/resources (grep evidence); logs stripped in release.
- TV: filename sanitization against path traversal (`../`, absolute paths, NUL) from
  `Content-Disposition`; downloads only to granted tree URIs; persisted permissions minimal.
### 4.8 Subscriptions & billing
- Backend-only entitlement decisions; purchase tokens bound to `obfuscatedAccountId`;
  RTDN OIDC verification; replay/dedupe; simulator unreachable in prod; refunds revoke access.
### 4.9 Admin
- Separate identity, Argon2id, TOTP, lockout, CSRF, cookie flags, RBAC matrix, audit immutability,
  PII masking + reason-gated reveals, IP allowlist option, no secrets in system config.
### 4.10 Rate limiting & abuse
- Inventory every limit (table in the audit doc); behaviour when Redis is down: **fail closed** for
  OTP/pairing/login; fail open (with logging) for read endpoints. Test both.
### 4.11 Logging & monitoring
- Redaction tests for every sensitive field; URL query strings stripped; phone numbers masked;
  security events logged (login, OTP lock, refresh reuse, admin actions); log retention policy.
### 4.12 Secrets & keys
- Inventory: JWT keys, OTP pepper, IP salt, pairing pepper, token encryption keys, billing keys,
  FCM/Play service accounts, SMS keys, DB/Redis credentials, admin TOTP key.
- Rotation runbook per secret (`docs/ops/secret-rotation.md`); key versioning for encrypted
  columns (`v1:` prefix) so re-encryption can happen gradually.
### 4.13 Privacy (India DPDP Act 2023 — basics, not legal advice)
- Data inventory & purpose table; consent/notice text in onboarding & privacy policy; user rights:
  access/export (`GET /api/v1/users/me/export` → JSON of the user's data, rate-limited), correction
  (profile/video edits), erasure (account deletion); retention schedule; breach-response steps in the
  incident runbook (Phase 17). Have counsel review before launch.

---

## 5. `docs/security-audit.md` structure

1. Scope, date, commit, method, tools and versions.
2. Architecture & trust boundaries diagram; asset inventory.
3. **Threat model** (STRIDE per component: phone app, TV app, API, WS, workers/inspection, DB,
   Redis, admin web, third parties: SMS, Play, FCM).
4. Checklist (§4) with Pass/Fail/N.A., evidence links (file:line, test names, command output).
5. **Findings table**: ID, title, area, severity (Critical/High/Medium/Low/Info), description,
   impact, fix/commit, status, regression test.
6. Accepted risks with justification and owner.
7. Scanner results summary.
8. Recommendations for Phase 17 (infra: WAF/rate limiting at edge, egress firewall for inspection,
   DB network isolation, backups encryption, secrets manager).

Also add `SECURITY.md` (vulnerability disclosure contact & policy) at repo root.

## 6. Acceptance criteria
- [ ] Scanners run in CI; zero unresolved High/Critical.
- [ ] Every checklist item has a result and evidence.
- [ ] All Critical/High findings fixed with regression tests; Medium/Low triaged with plans.
- [ ] Generated cross-tenant and permission suites green.
- [ ] JWT key rotation (`kid` + key ring) implemented and tested.
- [ ] SMS-pumping protections (country allowlist, budget breaker, Integrity flag) implemented.
- [ ] Data export endpoint and privacy inventory complete.
- [ ] `docs/security-audit.md`, `docs/ops/secret-rotation.md`, `SECURITY.md` written; report done.

## 7. Pitfalls
- Don't "fix" by disabling features silently; every behavioural change goes in the report.
- Scanners produce noise — triage, don't blanket-ignore; record suppressions with reasons.
- Timing-equality tests are noisy; measure many samples and compare medians.
