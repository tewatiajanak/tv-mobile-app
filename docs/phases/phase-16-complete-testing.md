# Phase 16 — Complete Testing (Full QA)

> **Implement this phase only. Do not start Phase 17. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-16-complete-testing.md
and all phase reports. We are implementing Phase 16 (Complete Testing) only.
Measure current coverage (backend and Android), list every existing test suite, and map them to the
test plan areas in the phase file. Give me a numbered plan: gaps to fill, new harnesses (load tests,
network chaos, migration/upgrade tests, device matrix), the test-plan document, and how results will
be recorded. Do not write code yet.
```

**Prompt B — implement**
```
Go. Write docs/testing/test-plan.md first, then fill the gaps area by area, add the harnesses,
and wire suites into CI (fast suites on PRs, slow suites nightly). Fix real bugs you find with
regression tests and list them. Commit per area.
```

**Prompt C — execute & report**
```
Execute the full plan: all automated suites, load tests, chaos tests, and generate the manual test
checklists for the human. Record everything in docs/testing/test-results-<date>.md (pass/fail,
metrics, defects, open risks, release-readiness verdict). Write docs/phase-reports/PHASE-16-report.md,
update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failing test / defect>. Reproduce, root-cause, fix with a regression test, re-run the affected suite, commit, update the results doc.
```

---

## 1. Goal

Evidence that VideoBridge works: a written test plan, automated suites covering every layer,
device/network/chaos/performance/security testing, manual scripts for what only a human with a
remote and a USB drive can verify, and a results report with a clear release-readiness verdict.

## 2. Test plan document (`docs/testing/test-plan.md`)

Sections: objectives; scope/out of scope; test levels (pyramid); environments (local, CI,
staging); **device matrix**; test data strategy (factories, seeded accounts, media fixtures);
entry/exit criteria; defect severity definitions (S1 blocker … S4 cosmetic); traceability
matrix (feature → tests); schedule of suites (PR vs nightly vs pre-release); roles (Claude
automated / human manual); risks.

### Device matrix (adjust to what's available; record actuals)
| Form factor | Emulators | Real devices (target) |
|---|---|---|
| Phone | API 26, 30, 34, 35/36; small (5") and large (6.7") + tablet | 1 low-end Android 10–11, 1 recent Pixel/Samsung |
| Android TV | API 28 (Android TV 9), 31, 34 at 1080p and 720p | 1 Google TV device (Chromecast/Google TV Streamer), 1 low-end TV (e.g. Mi/TCL/Sony Android TV), with a FAT32 USB stick, an exFAT USB stick, and an NTFS USB HDD |

## 3. Automated suites (fill gaps; targets are minimums for new/changed code)

### Backend
| Suite | Content | Target |
|---|---|---|
| Unit | services, pure logic (state machines, planners, merge, classifier, pipeline) | ≥ 85 % lines in `modules/*` logic |
| Integration | Prisma repositories against real Postgres; Redis rate limiters; BullMQ jobs | all repositories |
| API (e2e) | every OpenAPI operation: happy path + validation + auth + error envelope (generated skeleton + hand cases) | 100 % operations |
| Authorization | generated cross-tenant + admin permission matrix (Phases 13/15) | 100 % id routes |
| WebSocket | auth, isolation, replay, gaps, backpressure, commands, progress coalescing | all message types |
| Contract | event schemas (Phase 11), OpenAPI ↔ Android DTO decode fixtures | all events/DTOs |
| Migration | apply all migrations from empty; apply each migration on a DB seeded at the previous version; `prisma migrate diff` clean | all |
| Sync simulator | Phase 11 chaos runs (PR small, nightly large) | converge |

### Android (phone + TV)
| Suite | Content |
|---|---|
| Unit | ViewModels, repositories, SyncManager, outbox, downloader core, QueuePlanner, readiness, parsers |
| Room migrations | `MigrationTestHelper` from **every** exported schema version to latest |
| UI (Compose) | navigation, auth flows (phone + TV keypad), offline states, library, settings, D-pad focus suites |
| Screenshot | phone + TV screens, light/dark, font scales, empty/error states |
| Instrumented downloads | TV emulator + fixture server: complete, resume, unmount/remount (`sm`), network toggle, kill/restart |
| Playback | instrumented: MP4/HLS/DASH fixtures play, resume, error mapping |
| Upgrade | install previous release build → create data/downloads → install new build → user still signed in, library intact, downloads resume |
| Accessibility | Compose a11y checks on phone screens |

## 4. Specialized testing

### 4.1 Network interruption & chaos
- Backend: `toxiproxy` in `infra/docker-compose.test.yml` between API ↔ Postgres/Redis and in front
  of the fixture media server: latency, bandwidth caps, resets, timeouts.
- Scenarios: Redis down (rate limits fail closed for OTP/pairing, open for reads; WS fan-out
  degrades to catch-up), Postgres failover/restart (requests fail fast, recover), API restart
  during active WS sessions and TV downloads (downloads unaffected; sockets reconnect; no lost events),
  media server resets mid-download (resume), slow DNS.
- Android: emulator `-netspeed`/`-netdelay`, `adb shell svc wifi/data disable|enable`, airplane mode,
  captive portal simulation (HTTP 302 to a login page → downloads must detect HTML instead of media).

### 4.2 Performance & load (k6 in `tools/load/`)
| Scenario | Target (staging-sized instance) |
|---|---|
| 1,000 concurrent WS connections + 50 events/s fan-out | p95 delivery < 500 ms, no drops (seq gaps healed) |
| REST mix 200 rps (list videos, create, patch, sync/changes) | p95 < 300 ms, error rate < 0.1 % |
| OTP request flood (rate-limit correctness) | limits hold, no SMS beyond budget (fake provider) |
| Inspection queue 500 jobs | drains without starving; per-user limits hold |
| DB | `EXPLAIN ANALYZE` of top 20 queries at 1M videos / 100k users seeded — no seq scans on hot paths |
Android: startup and scroll macrobenchmarks (phone + TV) compared with Phase 9/10 baselines;
memory on a 1 GB TV profile during download + playback (no OOM over 2 h).

### 4.3 TV hardware scripts (human, with checklists generated by Claude)
- Remote: every screen D-pad only; number keys; media keys; Assistant voice "pause/play".
- Playback: each container/codec fixture + real-world files (HEVC 4K, AC-3 audio) → record what plays per device.
- Downloads: FAT32 stick with a 5 GB file (FILE_TOO_LARGE_FOR_FILESYSTEM), exFAT stick, NTFS HDD
  (read-only detection), unplug during download, replug, TV power-off (hard) mid-download, reboot,
  disk full, 24-hour soak downloading 10 large files with a schedule window.
- Progressive playback on a slow USB 2.0 stick.
- Results go into `docs/device-compatibility.md` (model, Android version, what works, quirks).

### 4.4 Subscription & entitlement testing
- Full entitlement boundary matrix (Phase 8) + state machine table re-run.
- Play internal-testing track with license testers: real purchase, upgrade, downgrade, cancel,
  grace (test card "declined"), refund from Play Console → RTDN → access revoked.

### 4.5 Security tests
- Phase 15 automated suites + scanners; ZAP API scan against staging; manual spot checks of
  pairing/OTP brute force and IDOR using the scripts in `tools/security/`.

## 5. CI wiring
- **PR (fast, < 15 min):** lint, unit, API e2e, contract, migration, small sync-sim, Android unit + Robolectric + screenshot diff.
- **Nightly:** full sync-sim, instrumented phone/TV emulator suites (GitHub Actions with
  `reactivecircus/android-emulator-runner` or Firebase Test Lab), load test smoke against staging,
  scanners, chaos suite.
- Flaky-test policy: quarantine requires an issue + owner; never silently retry-until-green.

## 6. Results document (`docs/testing/test-results-<date>.md`)
Executive summary and **release verdict** (Go / Go with known issues / No-go); per-suite table
(total/passed/failed/skipped, duration, link to CI run); coverage numbers; performance metrics vs
targets; device matrix results; defects found (ID, severity, status, fix commit); open risks;
manual checklist status (pending human sign-off items clearly marked).

## 7. File structure (new/changed)
```
docs/testing/{test-plan.md,test-results-<date>.md,manual/{tv-remote.md,tv-downloads.md,playback.md,phone.md,billing.md}}
tools/load/{k6 scripts,README.md}  tools/security/{otp-bruteforce.ts,pairing-bruteforce.ts,idor-check.ts}
infra/docker-compose.test.yml (toxiproxy)
backend/test/{api-generated/**,migrations/**,chaos/**}
android/**/src/test/** android/**/src/androidTest/** (gap fills)
.github/workflows/{nightly.yml,android-instrumented.yml}
```

## 8. Acceptance criteria
- [ ] Test plan complete with device matrix, entry/exit criteria and traceability.
- [ ] Coverage targets met or gaps justified; every OpenAPI operation and event covered.
- [ ] Room and Prisma migration suites pass from every historical version; app upgrade test passes.
- [ ] Network chaos, load and performance targets met (or deviations documented with plans).
- [ ] TV hardware/manual checklists generated; results recorded for at least one real TV (human).
- [ ] Billing tested in Play internal testing (human-assisted) and via simulator.
- [ ] Security suites and scanners green.
- [ ] CI split into PR and nightly suites; flaky policy documented.
- [ ] Results doc with a clear release verdict; all S1/S2 defects fixed.

## 9. Pitfalls
- Emulators hide real-world TV problems (USB filesystems, decoders, RAM) — the human real-device pass is mandatory before release.
- Load tests against shared staging can disrupt other testing — schedule them and reset data after.
- Don't let coverage targets drive meaningless tests; prioritise behaviour and failure paths.
