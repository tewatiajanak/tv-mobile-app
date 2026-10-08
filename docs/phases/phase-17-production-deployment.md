# Phase 17 — Production Deployment

> **Implement this phase only. Do not start Phase 18. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-17-production-deployment.md,
docs/security-audit.md and the latest phase report. We are implementing Phase 17 (Production Deployment) only.
Ask me which hosting provider, region, domain and DNS provider I want to use if they aren't already
recorded in docs/decisions — propose the default from the phase file. Then give me a numbered plan:
infrastructure layout per environment, Docker/Compose (or chosen platform) config, reverse proxy +
TLS, managed Postgres/Redis, secrets handling, CI/CD pipelines, migrations strategy, monitoring,
logging, alerting, backups + restore test, static web (legal pages, assetlinks), and the docs to
write. List every step that needs me to create an account, pay, or enter a secret. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement everything that can live in the repository (configs, pipelines, scripts, docs) step
by step and commit. For steps that need my accounts or secrets, write exact instructions in
docs/ops/ and pause to let me do them. Deploy to staging first and run the smoke tests; production
only after I say "deploy production".
```

**Prompt C — verify & report**
```
Run the staging smoke suite and a backup → restore drill into a scratch database, show the
monitoring dashboards and a test alert firing, and verify TLS/headers with curl. Tick acceptance
criteria, write docs/phase-reports/PHASE-17-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failing pipeline/log>. Root-cause first, explain briefly, smallest fix, re-run, commit.
```

---

## 1. Goal

Separate **development, staging and production** environments; a hardened, Dockerized backend
behind HTTPS on your domain; managed PostgreSQL and Redis; automated, safe migrations; CI/CD with
staging auto-deploy and gated production deploys; monitoring, logging, alerting; tested backups;
and the operational documents to run it all.

## 2. Default architecture (change via ADR if you prefer another provider)

Start simple, scale later:

```
                Cloudflare DNS (+ optional proxy/WAF, rate limiting at edge)
                                  │
                 ┌────────────────┴──────────────────┐
                 │ VPS / VM (Mumbai region, 2–4 vCPU) │  ← staging: smaller VM, same layout
                 │  Caddy (TLS, HTTP/2, headers)      │
                 │   ├─ api.<domain>   → backend ×2   │  (Docker Compose, rolling restart)
                 │   ├─ admin.<domain> → admin-web    │  (static) + /api/v1/admin via backend
                 │   └─ <domain>, link.<domain>       │  static site: landing, privacy, terms,
                 │        → web/ (static)             │  account deletion, /.well-known/assetlinks.json
                 │  worker container (BullMQ: inspection, notifications, reports, billing jobs)
                 └──────┬────────────────────┬───────┘
          Managed PostgreSQL 16 (PITR)   Managed Redis 7 (or Redis container with AOF)
```
- Region close to users (e.g. `ap-south-1` Mumbai / `blr1` Bangalore).
- **Inspection egress control:** the worker that inspects URLs runs in its own container with an
  egress policy that blocks private ranges at the network level too (iptables/Docker network or a
  dedicated egress proxy) — defense-in-depth for SSRF.
- Scale path (documented, not built): multiple app VMs behind a load balancer; WebSocket needs no
  sticky sessions thanks to Redis fan-out; workers scaled independently; read replica for admin reports.

## 3. Containers & config
- `backend/Dockerfile` hardened: multi-stage, `node:<lts>-alpine` (or distroless), non-root,
  read-only root FS, `NODE_ENV=production`, `--enable-source-maps`, healthcheck, `tini` as PID 1,
  graceful shutdown (drain HTTP, close WS with 1012 "service restart", finish BullMQ jobs).
- Separate process roles from one image: `api` and `worker` (`ROLE` env).
- `infra/prod/docker-compose.yml` + `infra/staging/docker-compose.yml`; `Caddyfile` with:
  automatic HTTPS, HSTS (`max-age=31536000; includeSubDomains`), security headers, gzip/zstd,
  request body limits, **WebSocket and long-poll timeouts ≥ 75 s** (Phase 3 long-poll, WS idle 60 s),
  real client IP forwarding (`TRUST_PROXY=true` only behind Caddy/Cloudflare), admin IP allowlist option.
- Environment files on the server (`/etc/videobridge/<env>.env`, `chmod 600`, owned by a deploy
  user) **or** a secrets manager (Doppler / 1Password / cloud secret manager). Never in git.
- Separate Firebase projects, Play RTDN topics, SMS sender configs and databases per environment.

## 4. Database & migrations
- Managed Postgres with automated daily backups + point-in-time recovery (≥ 7 days), TLS required,
  private networking/IP allowlist, separate DB users: `vb_migrator` (DDL) and `vb_app` (DML only;
  `INSERT/SELECT` only on `audit_logs`).
- Release step: `prisma migrate deploy` runs as a one-off container **before** new app containers
  start; the pipeline stops on failure.
- **Expand/contract** rule for breaking changes (documented in `docs/ops/migrations.md`); new
  code must run against both old and new schema during rollout; rollbacks are app-only
  (forward-fix the schema).
- Connection pooling (PgBouncer or provider pooler) sized for API + workers.

## 5. CI/CD (GitHub Actions)
```
on push to main:
  test (existing workflows) → build & push image ghcr.io/<org>/videobridge-backend:<sha>
  → deploy-staging (environment: staging): ssh/deploy script → migrate → rolling restart api → worker
  → smoke tests against staging (health, OTP with staging test number, pairing, create/list video, WS ping, inspection)
on manual "Deploy production" (workflow_dispatch, environment: production with required reviewers):
  same image digest (no rebuild) → backup snapshot trigger → migrate → rolling restart → smoke tests → tag release
admin-web: build → upload static files → cache-busting
web/ static: build → upload
android: (Phase 18) build signed AABs → upload to Play internal track
```
- Pin actions by SHA; least-privilege `GITHUB_TOKEN`; OIDC to cloud where possible; deploy key
  scoped to the deploy user; environment secrets only in GitHub Environments.
- Rollback: redeploy the previous image digest (`make rollback ENV=prod TO=<sha>`).

## 6. Observability
- **Metrics:** `/metrics` (Prometheus format, protected — internal network or basic auth) exposing
  HTTP RED metrics, WS gauges (Phase 11), queue depths, job durations, SMS sent/cost, RTDN
  processed/failed, DB pool stats. Scrape with Grafana Cloud agent (or self-hosted Prometheus).
- **Dashboards (as code in `infra/grafana/`):** API overview, WebSocket & sync, queues & workers,
  downloads (from backend mirror: started/completed/failed by code), billing, SMS/OTP.
- **Logs:** JSON to stdout → Loki/Grafana Cloud or provider logging; 30-day retention; redaction verified in prod config.
- **Errors:** Sentry for backend, admin-web and both Android apps (PII scrubbing: strip URLs'
  query strings, phone numbers, tokens; `sendDefaultPii=false`), release tracking per version.
- **Uptime:** external checks for `api /health/ready`, `admin`, `web`, WS handshake; public status page optional.
- **Alerts** (to email/Slack/Telegram): 5xx > 1 % for 5 min; p95 latency > 1 s; ready check failing;
  WS connections drop > 50 % in 5 min; queue backlog > threshold; DB CPU > 80 % / storage > 80 %;
  Redis memory > 80 %; SMS spend over daily budget; RTDN failures; backup job failed; certificate expiry < 14 days.

## 7. Backups & recovery
- Postgres: provider PITR + nightly logical dump (`pg_dump -Fc`) encrypted (age/GPG) to separate
  object storage in another region; retention 30 daily / 12 monthly.
- Redis: AOF enabled (BullMQ state); treated as recoverable (jobs re-derivable) — document what's lost.
- **Restore drill** script `infra/scripts/restore-drill.sh`: restore latest dump to a scratch DB,
  run migrations status + row-count sanity checks, report; scheduled monthly (CI) with alert on failure.
- Targets: **RPO ≤ 15 min, RTO ≤ 2 h** (documented; adjust to provider capabilities).

## 8. Static web (`web/`)
Minimal static site (Astro or plain HTML) served by Caddy: landing page, `/privacy`, `/terms`,
`/support`, `/delete-account` (explains in-app deletion + a form or email for users without the app —
required for Play), `/pair` & `/plans` fallback pages (App Links targets with install buttons),
`/.well-known/assetlinks.json` (SHA-256 of the **Play App Signing** certificate + upload cert for
internal builds) for `link.<domain>` / `<domain>`.

## 9. Documentation (`docs/ops/`)
| Doc | Content |
|---|---|
| `deployment.md` | environments, prerequisites, first-time setup (accounts, DNS, secrets), deploy, rollback |
| `operations.md` | runbooks: restart, scale, rotate secrets (link Phase 15), revoke an admin, maintenance mode, SMS provider outage/switch, RTDN backlog replay, inspection abuse, Redis full, disk full, certificate issues |
| `backup-recovery.md` | backup schedule, restore steps, drill log, RPO/RTO |
| `incident-response.md` | severities (SEV1–4), on-call, first 15 minutes checklist, comms templates (in-app maintenance banner via system config), data-breach steps (DPDP notification duties — legal review), postmortem template |
| `monitoring.md` | dashboards, alerts, thresholds, where logs live |
| `migrations.md` | expand/contract rules, release order |
| `billing.md`, `sms.md`, `push.md` | (from earlier phases) updated with production values (no secrets) |

## 10. Acceptance criteria
- [ ] Staging and production fully separated (DBs, Redis, secrets, Firebase, Play RTDN, SMS config, domains).
- [ ] HTTPS with A-grade TLS, HSTS and security headers on api/admin/web (curl/ssllabs evidence).
- [ ] WebSocket and pairing long-poll work through the proxy (timeouts verified).
- [ ] CI/CD: main → staging auto deploy with smoke tests; production deploy gated by approval and uses the same image digest; rollback works.
- [ ] Migrations run as a separate step with least-privilege roles.
- [ ] Metrics, dashboards, logs, Sentry and uptime checks live; a test alert fires and is received.
- [ ] Backups automated and encrypted off-site; restore drill succeeded and is scheduled.
- [ ] Static web pages and `assetlinks.json` live; App Links verify on a device.
- [ ] Ops documentation complete.

## 11. Pitfalls
- Cloudflare proxy + WebSockets: enable WS support and keep idle timeouts in mind (100 s on free plans).
- `TRUST_PROXY` misconfiguration breaks rate limits (everyone shares one IP) — test from two networks.
- Running migrations from app startup in multiple replicas causes races — keep it a single release step.
- Don't let staging send real SMS to random numbers — restrict staging to test numbers/allowlist.
