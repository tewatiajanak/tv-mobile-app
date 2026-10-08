# Phase 14 — Notifications

> **Implement this phase only. Do not start Phase 15. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-14-notifications.md
and the latest phase report. We are implementing Phase 14 (Notifications) only.
Inspect where each triggering event originates (videos, devices, downloads, storage, subscriptions,
schedules) and the existing fcm_token handling. Give me a numbered plan: notification types and
defaults, the delivery pipeline (preferences, quiet hours, dedupe, coalescing, rate limits, origin
and foreground suppression), FCM adapter, in-app inbox + WebSocket delivery, triggers per type,
cron jobs (expiry reminders, digests), Android phone (FCM service, channels, permission timing,
inbox), TV (in-app toasts/inbox), preferences UI, and tests. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 14 step by step: schema → pipeline (pure, fake-clock tested) → FCM adapter →
inbox APIs + WS → triggers one type at a time → crons → Android phone → TV → preferences UI → tests.
Build, test and commit after each step. Never include tokens or full URLs in notification payloads.
```

**Prompt C — verify & report**
```
Run all tests. On the emulators (phone with Google Play image for FCM), trigger every notification
type and show it arriving (or being suppressed/coalesced as designed): quiet hours, coalesced
"3 new videos", overnight digest, storage disconnected, payment failed, device removed. Tick
acceptance criteria, write docs/phase-reports/PHASE-14-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run related + full tests, commit.
```

---

## 1. Goal

Users hear about what matters — a TV connected, a download finished or failed, a USB drive
disconnected, a payment problem, a subscription about to expire, a device removed, scheduled
downloads — through push on the phone and in-app messages on phone and TV, **without spam**,
with clear per-type preferences.

## 2. Prerequisites
Phases 3–13 (all triggering events exist; `devices.fcm_token`; WS; schedules; subscriptions; admin config).

## 3. Scope
**In:** notification types & defaults, preferences, quiet hours, inbox, delivery pipeline with
anti-spam, FCM push (phone), WS in-app delivery (phone + TV), triggers, reminder/digest crons,
Android implementation, TV in-app toasts and inbox, admin visibility (counts) optional.
**Out:** email/SMS notifications, marketing campaigns.

---

## 4. Notification types & defaults

| Type | Trigger | Push (phone) | In-app | Critical* | Coalescing / dedupe |
|---|---|---|---|---|---|
| `NEW_VIDEO` | video created by **another** device | off | on (TV toast) | no | coalesce 2 min → "3 new videos added" |
| `TV_CONNECTED` | pairing approved / new TV device | on | on | yes (security) | dedupe per device |
| `DOWNLOAD_STARTED` | TV job → DOWNLOADING (first time) | off | on | no | dedupe per job |
| `DOWNLOAD_COMPLETED` | job COMPLETED | on | on | no | coalesce 5 min per TV; during a schedule window → digest |
| `DOWNLOAD_FAILED` | job FAILED (terminal) | on | on | no | dedupe per job+code; coalesce 5 min |
| `STORAGE_DISCONNECTED` | location unavailable **while jobs are active/queued on it** | on | on (TV banner) | no | dedupe per location per 30 min |
| `SUBSCRIPTION_EXPIRING` | 7 d and 1 d before `currentPeriodEnd` for CANCELLED / trial end | on | on | no | once per threshold |
| `SUBSCRIPTION_EXPIRED` | → EXPIRED | on | on | no | once |
| `PAYMENT_FAILED` | → GRACE_PERIOD / PAST_DUE | on | on | yes | once per state entry |
| `DEVICE_REMOVED` | device revoked (to remaining devices; the removed TV shows its own screen) | on | on | yes (security) | dedupe per device |
| `SCHEDULED_DOWNLOADS` | window start with N jobs ("5 downloads will start at 11 PM" — optional), window end digest ("4 finished, 1 failed overnight") | digest on | on | no | one digest per window |

*Critical = bypasses quiet hours and rate limits (still respects the user turning the type off,
except `DEVICE_REMOVED`/`TV_CONNECTED` which are always in-app).

---

## 5. Data model (migration `…_notifications`)

```prisma
model Notification {
  id         String   @id @db.Uuid
  userId     String   @map("user_id") @db.Uuid
  type       String   @db.VarChar(40)
  title      String   @db.VarChar(120)
  body       String   @db.VarChar(400)
  data       Json                                 // deep link target ids only (videoId, jobId, deviceId) — no URLs/tokens
  dedupeKey  String?  @map("dedupe_key") @db.VarChar(200)
  groupKey   String?  @map("group_key") @db.VarChar(100)
  channels   String[]                             // PUSH, IN_APP
  status     String   @db.VarChar(20)             // PENDING, DEFERRED, SENT, SUPPRESSED, COALESCED
  suppressedReason String? @map("suppressed_reason") @db.VarChar(40)
  deliverAfter DateTime? @map("deliver_after") @db.Timestamptz   // quiet hours / coalescing
  readAt     DateTime? @map("read_at") @db.Timestamptz
  createdAt  DateTime @default(now()) @map("created_at") @db.Timestamptz
  sentAt     DateTime? @map("sent_at") @db.Timestamptz
  @@index([userId, createdAt(sort: Desc)])
  @@unique([userId, dedupeKey])
  @@map("notifications")
}
model NotificationPreference {
  userId      String  @map("user_id") @db.Uuid
  type        String  @db.VarChar(40)
  pushEnabled Boolean @map("push_enabled")
  inAppEnabled Boolean @map("in_app_enabled")
  @@id([userId, type])
  @@map("notification_preferences")
}
```
`users` gains `quiet_hours_start smallint`, `quiet_hours_end smallint` (minutes), `quiet_hours_tz text`
(defaults 22:00–08:00, `Asia/Kolkata` or the phone's zone), `notifications_digest boolean default true`.
Missing preference rows → defaults from the §4 table (kept in `notification-defaults.ts`, overridable via system config).
Retention: delete notifications older than 90 days.

---

## 6. Delivery pipeline (`NotificationsService.notify`)

```
notify(userId, type, payload, { dedupeKey?, groupKey?, originDeviceId?, critical? })
 1. Build title/body from templates (i18n-ready keys + params); data = ids only.
 2. Dedupe: insert with (userId, dedupeKey) unique → conflict = drop silently.
 3. Preferences: drop channels the user disabled (critical security types keep IN_APP).
 4. Coalescing: if a groupKey window is open (Redis `notif:group:{userId}:{groupKey}` with TTL),
    mark COALESCED and update the group's pending summary; the flush job sends one summary.
 5. Quiet hours (non-critical): if now in the user's quiet window → DEFERRED until the window ends.
 6. Rate limits (non-critical): ≤ 10 push/hour, ≤ 30 push/day per user → beyond that IN_APP only.
 7. Targeting: push to the user's PHONE devices with fcm_token, **excluding originDeviceId**;
    in-app via WS `NOTIFICATION_CREATED` to all devices (TV included).
 8. Foreground suppression: if the target phone has an active WS connection and reported
    `appState=FOREGROUND` in the last 60 s → skip push for that device (in-app shows instead).
 9. Persist status (SENT/SUPPRESSED/…) and reason for observability.
```
A BullMQ queue `notifications` runs steps 7–9 with retries; a flush job handles DEFERRED and
COALESCED groups (every minute). The pipeline logic is a **pure function** of
`(event, prefs, quietHours, now, rateState, groupState)` → decision, unit-tested with a fake clock.

### FCM adapter
- `firebase-admin` with service account from secret env (`FCM_SERVICE_ACCOUNT_JSON`).
- Data + notification message, `android.priority = normal` (high only for critical),
  `collapse_key = groupKey`, `ttl` 1 day (critical 7 days), channel id per type.
- Handle errors: `messaging/registration-token-not-registered` or `invalid-argument` → clear
  that device's `fcm_token`; quota errors → retry with backoff.
- Never put URLs, phone numbers or tokens in the payload; titles may contain the video title
  (truncate to 60 chars).

---

## 7. API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/notifications?cursor=&limit=` | inbox (newest first) |
| GET | `/api/v1/notifications/unread-count` | |
| POST | `/api/v1/notifications/{id}/read` | |
| POST | `/api/v1/notifications/read-all` | |
| GET | `/api/v1/notifications/preferences` | `{ types: [{type, pushEnabled, inAppEnabled, critical, label}], quietHours: {start, end, tz}, digest }` |
| PUT | `/api/v1/notifications/preferences` | validated; critical security in-app cannot be disabled |
| PUT | `/api/v1/devices/current/push-token` | (exists) also accepts `null` to unregister |
| WS in | `APP_STATE {state: FOREGROUND|BACKGROUND}` | phone reports for foreground suppression |

## 8. Triggers (wire through domain events, not controllers)
- Subscribe to `SyncService`/domain events in a `NotificationsTriggers` module:
  video created (origin device excluded), device connected/removed, download state changes (from
  the TV's PATCH), storage location `isAvailable → false` with affected jobs count, subscription
  state changes. Cron (hourly): expiring subscriptions at 7 d / 1 d thresholds. Schedule window
  start/end (from Phase 12 schedules) → digest built from jobs finished inside the window.

## 9. Android — phone
- Firebase Messaging dependency; `google-services.json` per flavor provided by CI secrets
  (`.gitignore`d; document how to place it locally). Builds without it must still compile
  (guard the plugin or use a placeholder for `dev` — document the choice).
- `VbMessagingService.onNewToken` → upload token; `onMessageReceived` → build notification with
  the channel for its type, group downloads (`setGroup` + summary), `PendingIntent` deep link
  (`videobridge://video/{id}`, `…/downloads`, `…/devices`, `…/subscription`), immutable flags.
- Channels: *Library*, *Downloads*, *Storage*, *Account & security*, *Subscription & billing*
  (users can tune them in system settings too; link from the app).
- **POST_NOTIFICATIONS** (Android 13+) asked **in context** — after the first TV is connected or
  first download is started from the phone, with a pre-prompt explaining value; never on first launch.
- **Inbox**: bell icon with unread badge in Home's top bar → list grouped by day, tap → deep link,
  mark read, "Mark all read".
- **Preferences** (Settings → Notifications): per type push/in-app toggles with defaults,
  quiet hours (time pickers + zone), overnight digest toggle, system permission state.
- Report `APP_STATE` on foreground/background via WS.

## 10. Android — TV
- No push UI dependency: TV receives `NOTIFICATION_CREATED` over WS and shows a non-blocking
  `TvToast`/banner (top-right, 5 s, never steals focus) for `NEW_VIDEO` (coalesced), `DOWNLOAD_FAILED`,
  `STORAGE_DISCONNECTED`, `SUBSCRIPTION_EXPIRED`; `DEVICE_REMOVED` for itself is handled by Phase 3 logic.
- Locally detected storage removal shows a banner immediately (no server round trip).
- TV inbox: Settings → Notifications (list, mark read). Preferences are edited on the phone (QR hint).

## 11. Tests
- Pipeline unit tests (fake clock): dedupe; preferences; quiet hours crossing midnight and time
  zones; coalescing windows produce exactly one summary with correct count; rate limits with
  critical bypass; origin exclusion; foreground suppression; digest content.
- FCM adapter: payload shape per type (no URLs/tokens), invalid token cleanup, retry on quota.
- E2E per trigger type (with a fake push transport capturing messages).
- Android: messaging service builds the right channel/group/deep link; permission pre-prompt
  timing; inbox ViewModel; TV toast never steals focus (D-pad test).

## 12. File structure (new/changed)
```
backend/src/modules/notifications/{notifications.module.ts,notifications.service.ts,pipeline.ts,templates.ts,notification-defaults.ts,fcm.transport.ts,inbox.controller.ts,preferences.controller.ts,triggers.ts,flush.job.ts,reminders.cron.ts,digest.service.ts}
backend/prisma/migrations/…_notifications
android/app-phone/…/notifications/{VbMessagingService.kt,NotificationChannels.kt,NotificationBuilder.kt,InboxScreen.kt,NotificationPreferencesScreen.kt,PermissionPrePrompt.kt}
android/app-tv/…/notifications/{TvToastHost.kt,TvInboxScreen.kt}
android/core/data/…/{NotificationsRepository.kt,AppStateReporter.kt}
docs/ops/push.md (Firebase project setup per environment)
```

## 13. Acceptance criteria
- [ ] All 11 types trigger correctly with sensible default channels.
- [ ] No duplicates (dedupe), bursts coalesced, quiet hours respected (critical bypass), rate limits applied, origin device not notified, foreground app not pushed.
- [ ] Overnight digest summarises scheduled downloads in one notification.
- [ ] Phone push works with deep links and channels; permission requested in context.
- [ ] TV shows non-intrusive in-app toasts and has an inbox; focus never stolen.
- [ ] Preferences UI works; critical security in-app notifications can't be disabled.
- [ ] Invalid FCM tokens cleaned up; payloads contain no URLs/tokens/phone numbers.
- [ ] All tests pass; docs updated.

## 14. Pitfalls
- FCM requires a Google Play–enabled emulator image; TV emulators often lack Play services — TV relies on WS by design.
- Coalescing must flush even if no further events arrive (timer-based flush job).
- Time-zone of quiet hours belongs to the user/phone, not the server.
