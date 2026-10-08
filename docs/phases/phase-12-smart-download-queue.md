# Phase 12 — Smart Download Queue

> **Implement this phase only. Do not start Phase 13. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-12-smart-download-queue.md
and the latest phase report. We are implementing Phase 12 (Smart Download Queue) only.
Inspect DownloadScheduler, DownloadWorker, HttpDownloader's throttle hook, the downloads backend
module, device settings and device commands. Give me a numbered plan: pure scheduling decision
function, priorities and ordering, preemption, pause/resume all and retry failed, schedules (time
windows), Wi-Fi-only/unmetered, bandwidth limiter, entitlement gating, backend schema/APIs and
commands, TV and phone UI, download history gating, and the test matrix. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 12 step by step starting with the pure QueuePlanner and its table tests, then
the throttle, then wiring into the scheduler/worker, then backend + commands, then UI. Build, test
and commit after each step.
```

**Prompt C — verify & report**
```
Run all tests including the QueuePlanner matrix and throttle accuracy test. On the TV emulator,
demonstrate priorities/reordering, preemption, pause all/resume all, retry failed, a schedule window
(use a window starting 2 minutes from now), Wi-Fi-only behaviour, and a 1 MB/s bandwidth limit.
Show FREE vs PRO gating. Tick acceptance criteria, write docs/phase-reports/PHASE-12-report.md,
update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, add a QueuePlanner case if relevant, run tests, commit.
```

---

## 1. Goal

Users control **what downloads first, when, and how fast**: priorities and manual ordering,
pause/resume all, retry all failed, **scheduled downloads in time windows** (e.g. 11 PM–6 AM) for
Pro/Family, optional **bandwidth limits** and a **Wi-Fi/unmetered-only** preference — all
respecting plan entitlements and controllable from both TV and phone.

## 2. Prerequisites
Phase 6 engine (throttle hook), Phase 8 entitlements (`scheduled_downloads`,
`bandwidth_controls`, `download_history`, active/queued limits), Phase 11 device commands and sync.

## 3. Scope
**In:** priorities (HIGH/NORMAL/LOW) + manual order, preemption, bulk actions, schedules,
unmetered-only, global bandwidth limiter (+ optional limit hours), download modes (Now /
Scheduled), entitlement gating and downgrade behaviour, backend persistence + sync + commands,
TV/phone UI, download history screen gating.
**Out:** per-host connection tuning, multi-connection segmented downloads (future), notifications (14).

---

## 4. Concepts

- **Priority:** `HIGH (2)`, `NORMAL (1)`, `LOW (0)`. DOWNLOAD button = HIGH, QUEUE = NORMAL
  (Phase 10). User can change it.
- **Order:** `queuePosition` (fractional index string, e.g. LexoRank-style) inside a priority;
  "Move up/down/to top" without renumbering everything.
- **Mode:** `NOW` (eligible whenever constraints allow) or `SCHEDULED` (eligible only inside an
  enabled schedule window). Requires `scheduled_downloads` for SCHEDULED.
- **Schedule:** per TV, one or more windows: `daysOfWeek` (bitmask), `startMinute`, `endMinute`
  (may cross midnight: 23:00→06:00), `timezone` (IANA, default TV's zone), `enabled`,
  `appliesTo: SCHEDULED_ONLY | ALL` ("Only download at night" = ALL).
- **Network policy:** `ANY` or `UNMETERED_ONLY` (Wi-Fi or Ethernet; TVs are often on Ethernet —
  label it "Wi-Fi or wired only (avoid mobile hotspots)"). Maps to WorkManager `NetworkType.UNMETERED`
  plus a runtime check (`NetworkCapabilities.NET_CAPABILITY_NOT_METERED`).
- **Bandwidth limit:** global for the TV (`bandwidthLimitBps`, null = unlimited), optional active
  hours (e.g. limit only 18:00–23:00). Requires `bandwidth_controls`.
- **Preemption:** when a HIGH job is queued and all slots are busy, pause the lowest-priority
  running job (`PAUSED(PREEMPTED)`) if the setting "Let urgent downloads go first" is on (default on).
  Never preempt a job that is being watched (progressive playback) or > 95 % complete.

## 5. QueuePlanner (pure Kotlin, the heart of this phase)

```kotlin
data class PlannerInput(
  val now: ZonedDateTime,
  val jobs: List<JobSnapshot>,             // state, priority, position, mode, locationAvailable, isBeingWatched, progressFraction
  val maxActive: Int,
  val schedules: List<ScheduleWindow>,
  val networkPolicy: NetworkPolicy,
  val network: NetworkSnapshot,             // connected, unmetered
  val entitlements: QueueEntitlements,      // scheduledDownloads, bandwidthControls
  val preemptForHigh: Boolean,
  val globallyPaused: Boolean               // "Pause all"
)
data class PlannerOutput(
  val start: List<String>, val pause: List<Pair<String, PauseReason>>,
  val nextWakeAt: ZonedDateTime?,           // next window start/end or limit-hours boundary
  val explanations: Map<String, String>     // why each waiting job waits (for UI)
)
```
Rules (in order): globally paused → nothing starts; network policy unmet → running jobs pause
`WAITING_FOR_WIFI`; window rules: SCHEDULED (or all jobs when `appliesTo=ALL`) outside a window
→ `OUTSIDE_SCHEDULE` (running ones pause at window end unless they'll finish within 2 min);
unavailable storage → skip; order candidates by priority desc, then `queuePosition`, then
`createdAt`; fill free slots; preempt if allowed; compute `nextWakeAt`. If the plan downgraded
and `scheduled_downloads` is false → SCHEDULED jobs are treated as NOW (with a one-time notice)
and schedules become inactive (kept, not deleted).

`DownloadScheduler.reconcile()` now: build `PlannerInput` → `plan()` → apply actions → enqueue a
`QueueWakeWorker` at `nextWakeAt` (WorkManager `setInitialDelay`; inexact by a few minutes — say
"around 11 PM" in UI). Also re-plan on time-zone change (`ACTION_TIMEZONE_CHANGED`), clock change
and boot.

## 6. Bandwidth limiter
- `TokenBucketThrottle` shared by all running downloads on the TV (rate = limit, burst = 1 s worth),
  `suspend fun acquire(bytes)`; fair across jobs (each job acquires per chunk).
- Respect limit hours; when outside them, `acquire` returns immediately.
- Speed/ETA in UI reflect the throttled rate.
- Test: download a 50 MB fixture at 2 MB/s limit → duration within ±10 %.

## 7. Backend

Migration `…_queue`:
- `download_jobs` add `queue_position text`, `mode text default 'NOW'`, `scheduled_by_user boolean` (if not present).
- `download_schedules(id, user_id, device_id, name, days_mask int, start_minute int, end_minute int, timezone text, applies_to text, enabled bool, created_at, updated_at, deleted_at)`.
- `devices.settings` keys: `networkPolicy`, `bandwidthLimitBps`, `bandwidthLimitHours {start,end}`,
  `preemptForHigh`, `defaultStorageLocationId` (Phase 9).

APIs:
| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/v1/devices/{tvId}/download-schedules` | create requires `scheduled_downloads` else `FEATURE_NOT_IN_PLAN` (403, `details.entitlement`) |
| PATCH/DELETE | `/api/v1/devices/{tvId}/download-schedules/{id}` | |
| PATCH | `/api/v1/devices/{tvId}/settings` | bandwidth fields require `bandwidth_controls`; validation (limit ≥ 128 KB/s) |
| PATCH | `/api/v1/downloads/{id}` | TV reports `priority`, `queuePosition`, `mode` changes too |
New device command types (Phase 11 mechanism): `SET_PRIORITY {jobId, priority}`, `MOVE_JOB {jobId, beforeJobId|afterJobId}`,
`SET_MODE {jobId, mode}`, `PAUSE_ALL`, `RESUME_ALL`, `RETRY_FAILED`. Events: `DOWNLOAD_STATE_CHANGED`
carries the new fields; `DEVICE_UPDATED` for settings; `DOWNLOAD_SCHEDULE_UPDATED` (persisted) for schedules.

**Download history:** `GET /api/v1/downloads/history?deviceId=&cursor=` — completed/failed/cancelled
jobs; if `download_history` is false, only the last 7 days are returned (`details.limitedTo: "7d"`).

## 8. TV UI
- Downloads screen: queue section shows priority badges and order; on a focused queued row:
  **Move up**, **Move down**, **Move to top**, **Priority ▸ High/Normal/Low**, **Download tonight / Download now** (mode).
  Header actions: **Pause all**, **Resume all**, **Retry failed (n)**. Waiting rows explain why
  ("Waiting for tonight's window (11 PM)", "Waiting for Wi-Fi/wired network", "Paused for an urgent download").
- Detail screen: DOWNLOAD long-press (or secondary button) → "Download tonight" when entitled.
- Settings → Downloads: Schedules (list/add/edit with D-pad time pickers: hour/minute steppers,
  day toggles; template "Every night 11 PM–6 AM"), Network (Any / Wi-Fi or wired only), Speed limit
  (Unlimited, 1, 2, 5, 10, 20 MB/s; optional limit hours), "Let urgent downloads go first".
  Locked items show the plan badge ("PRO") with "Upgrade from your phone" QR.
- History screen (gated as above).

## 9. Phone UI
- Downloads tab per TV: same queue controls via commands (Move up/down via drag handle, priority
  menu, mode toggle), Pause all / Resume all / Retry failed.
- TV settings page on phone: schedules editor (Material time pickers), network policy, speed limit —
  writes via the APIs above; locked features show the upgrade CTA.

## 10. Tests
- **QueuePlanner matrix** (≥ 40 cases): priorities/ordering; equal priorities by position; preemption on/off; no preemption of watched/near-complete jobs; windows crossing midnight; multiple windows; days mask at week boundary; time-zone change; `appliesTo=ALL`; unmetered policy with Wi-Fi, Ethernet, metered hotspot, no network; storage unavailable; maxActive 1/2/4; global pause; entitlement downgrade (SCHEDULED → NOW; schedules inactive); `nextWakeAt` correctness.
- Throttle accuracy (±10 %) and fairness across 2 jobs.
- Backend e2e: schedule CRUD gating, settings gating, commands (`MOVE_JOB`, `PAUSE_ALL` …) produce events, history limited for FREE.
- Android: command executor handles new types idempotently; UI tests for TV queue actions with D-pad.

## 11. File structure (new/changed)
```
android/tv/download/…/queue/{QueuePlanner.kt,PlannerInput.kt,ScheduleWindow.kt,FractionalIndex.kt,QueueWakeWorker.kt}
android/tv/download/…/engine/TokenBucketThrottle.kt
android/app-tv/…/downloads/{QueueActions.kt,HistoryScreen.kt}  android/app-tv/…/settings/downloads/{SchedulesScreen.kt,ScheduleEditor.kt,SpeedLimitScreen.kt,NetworkPolicyScreen.kt}
android/app-phone/…/downloads/{QueueControls.kt,TvDownloadSettingsScreen.kt,ScheduleEditorSheet.kt}
backend/src/modules/downloads/{schedules.controller.ts,schedules.service.ts,history.controller.ts}
backend/src/modules/devices/{device-settings.service.ts (gating)}
backend/prisma/migrations/…_queue
```

## 12. Acceptance criteria
- [ ] Priorities and manual ordering determine start order; DOWNLOAD (HIGH) vs QUEUE (NORMAL) behave as specified.
- [ ] Preemption works and never interrupts watched or nearly complete downloads.
- [ ] Pause all / Resume all / Retry failed work on TV and from the phone.
- [ ] Schedules (incl. 11 PM–6 AM crossing midnight) start and pause jobs at the right times (±5 min), survive reboot and time-zone change.
- [ ] Wi-Fi/wired-only policy pauses on metered networks and resumes on unmetered ones.
- [ ] Bandwidth limit accurate within ±10 %; limit hours respected.
- [ ] Gating: FREE can't create schedules or speed limits (backend + UI), PLUS can limit speed, PRO/FAMILY can schedule; downgrade keeps data but deactivates features gracefully.
- [ ] Waiting jobs always explain why they're waiting.
- [ ] History limited to 7 days without `download_history`.
- [ ] All tests pass; docs updated.

## 13. Pitfalls
- WorkManager delays are inexact; never promise exact minutes in the UI.
- Time windows must be evaluated in the schedule's time zone, not UTC, and re-evaluated after clock/zone changes.
- Don't implement bandwidth limits by sleeping per job only — a shared bucket is needed for a global limit.
- Preemption + automatic resume can thrash; require a running job to have run ≥ 30 s before it can be preempted.
