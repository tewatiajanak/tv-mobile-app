# Phase 9 — Phone UX (Polished Material 3 App)

> **Implement this phase only. Do not start Phase 10. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-09-phone-ux.md
and the latest phase report. We are implementing Phase 9 (Phone UX) only.
Inspect every existing phone screen, ViewModel, navigation graph and the designsystem module.
Give me a numbered plan: design tokens/components, navigation shell, each screen (Home, Library,
Add Video, Video Detail, Downloads, Devices, Settings + sub-screens, Onboarding), the small backend
additions (account deletion, device settings), state handling (loading/empty/error/offline),
accessibility, performance (Paging, baseline profile), and screenshot/UI tests. List which existing
screens you will refactor vs keep. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 9 step by step: design system → navigation shell → screens one at a time
(each with its states, previews, and tests) → backend additions → accessibility pass → performance
pass. Keep business logic in ViewModels/repositories. Build, test and commit after each screen.
```

**Prompt C — verify & report**
```
Run all tests, screenshot tests (light/dark/large font) and the macrobenchmark. Walk through the
manual test script on the emulator and attach screenshots of every main screen to the report.
Tick acceptance criteria, write docs/phase-reports/PHASE-09-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<describe the UI issue or paste failure>. Root-cause first, smallest fix, re-run the related tests, commit.
```

---

## 1. Goal

A phone app that feels finished: five top-level destinations (**Home, Library, Downloads,
Devices, Settings**), fast and friendly link saving (paste + Share), a rich library with
thumbnails and actions, clear status everywhere, complete settings (including account deletion
required by Google Play), and graceful loading, empty, error and offline states.

## 2. Prerequisites
Phases 2–8 (auth, devices/pairing, videos/sync, metadata/playback, downloads mirror, entitlements/plans).

## 3. Scope
**In:** design system, navigation, all phone screens, onboarding, account deletion (backend +
UI), per-TV download preferences, accessibility, dark theme, large screens, performance.
**Out:** remote TV download control (Phase 11 adds "Download on TV"/pause/resume from phone),
notification preferences content (Phase 14 — entry point only), TV UI (10).

---

## 4. Design system (`core:designsystem`)

- **Tokens:** color scheme (brand primary, secondary, tertiary; light + dark; dynamic color
  optional toggle in Settings), typography scale (Material 3), spacing (4/8/12/16/24/32),
  shapes (small 8, medium 12, large 20), elevation, motion durations.
- **Components** (each with `@Preview` light/dark and a screenshot test):
  `VbTopBar`, `VideoCard` (grid) and `VideoListItem` (list), `ThumbnailImage` (Coil 3, crossfade;
  fallback = colored tile from a hash of the domain + domain initial + play/stream/web icon),
  `StatusBadge` (Checking · Playable · Stream · Web page · Protected · Unreachable · Unknown),
  `DownloadChip` ("On Living Room TV ✓", "Downloading 43 %"), `CategoryChip`, `UsageBar`,
  `EmptyState(icon, title, body, action)`, `ErrorState(message, retry)`, `OfflineBanner`,
  `LimitBanner` (over-limit / entitlement), `SectionHeader(title, action)`, `ConfirmDialog`,
  `LoadingPlaceholder` (shimmer skeletons), `SnackbarHost` helpers with Undo.
- Status/reason texts come from one `StatusText` mapper shared by all screens (uses `strings.xml`).

## 5. Navigation shell

- Single activity, Compose Navigation with type-safe routes. `NavigationSuiteScaffold` (bottom
  bar on phones, rail on tablets/foldables).
- Top-level: Home, Library, Downloads, Devices, Settings. Global **Add** FAB on Home and Library.
- Deep links: `videobridge://video/{id}`, `https://<domain>/pair?c=`, `https://<domain>/plans`,
  share target → Add flow.
- Back stack per tab preserved (`saveState`/`restoreState`).
- Auth gate: signed-out → Login graph; after login resume pending deep link/share.

## 6. Screens

### 6.1 Onboarding (first launch only)
3 pages: "Save videos from any app" (illustration of WhatsApp → Share → VideoBridge), "Watch &
download on your TV", "Connect your TV in seconds" → Sign in. Skippable. Shown again via Settings → Help.

### 6.2 Home
Sections (each hidden when empty, whole screen empty-state when everything is empty):
1. Greeting + quick paste field ("Paste a video link") with Paste button.
2. **Continue watching** (from `/playback` list) — horizontal cards with progress bars.
3. **Recently added** — last 10.
4. **Downloads on your TVs** — active jobs with live progress (`DOWNLOAD_PROGRESS`).
5. **Your TVs** — cards with online dot, current download count, "Connect a TV" if none.
6. Plan/limit banner when near/over limits (≥ 90 % of `max_saved_links`, over-limit devices).
Pull to refresh triggers `SyncManager`.

### 6.3 Library
- **Paging 3** from Room (`PagingSource` from DAO) — must stay smooth with 10,000 links.
- Toggle grid/list (remembered). Sticky search bar with recent searches; filter sheet: category,
  status (playable/stream/unsupported/checking), source domain, downloaded on TV (yes/no), date
  added; sort (newest, oldest, title A–Z, recently watched).
- Item actions (overflow + long-press): Play on phone, Edit, Copy link, Share link, Re-check,
  Delete. Multi-select mode: bulk delete, set category.
- Category management: rename/merge categories (backend: `POST /videos/categories/rename {from,to}`
  — batch update in one transaction with sync events; add if missing).
- States: loading skeleton, empty ("Your library is empty — share a link from WhatsApp or tap +"),
  no results for search/filter (with "Clear filters"), error with retry, offline banner.

### 6.4 Add Video (full-screen on phones, dialog on tablets)
URL field (Paste button, clipboard read only on tap), live preview card from `/metadata/preview`
(thumbnail/og image, type, size, badge), title (prefilled suggestion, editable), category
(dropdown + create), notes, Save. Duplicate → "Already in your library" with "Open". Limit →
`LimitBanner` + See plans. Share flow (Phase 4 quick-save sheet) restyled with the same components.

### 6.5 Video Detail
Hero thumbnail, title, domain, badges, size/duration/type, notes, category, added date/device;
playback progress; downloads of this video on each TV (state, location label); actions: Play on
phone, Edit, Copy/Share link, Re-check, Delete. Reason card for unsupported/web-page videos with a
plain-language explanation.

### 6.6 Downloads (read-only view of TV downloads in this phase)
Grouped by TV, then by state (Downloading, Queued, Paused, Failed, Completed). Live progress,
speed, ETA, pause/failure reasons in plain words, storage label. Empty state "Start downloads from
your TV". (Phase 11 adds controls and "Download on TV".)

### 6.7 Devices
Polish of Phase 3: Phones / TVs sections, online status, last seen, "This phone", rename, remove,
Connect a TV (scanner + code), per-TV **download preferences**: default storage location (from
`/storage/locations?deviceId`), shown with free space.
Backend addition: `devices.settings jsonb` + `PATCH /api/v1/devices/{id}/settings { defaultStorageLocationId }`
(validated: location belongs to that device) → `DEVICE_UPDATED`; the TV honours it in its storage picker.

### 6.8 Settings
| Section | Content |
|---|---|
| Account | display name (edit), phone (masked), Sign out, Sign out of all devices, **Delete account** |
| Subscription | plan, status, renewal/expiry date, usage bars, See plans, Manage subscription, Payments & invoices |
| Devices | link to Devices |
| Storage preferences | per-TV default download location (same as 6.7) |
| Playback | Resume behaviour (Ask / Always resume / Start over), phone playback on mobile data (allow/ask) |
| Notifications | system permission state + entry to preferences (filled in Phase 14) |
| Appearance | theme (System/Light/Dark), dynamic color |
| Privacy | what VideoBridge stores (plain-language list), clear local cache, privacy policy link |
| Help & legal | How to save from WhatsApp/Telegram/Chrome, FAQ, contact support (mailto with app version + device id), Terms, Privacy policy, open-source licenses |
| About | version/build/flavor, backend environment (non-prod only) |

### 6.9 Account deletion (Play policy requirement)
- Backend: `DELETE /api/v1/users/me` `{ confirm: "DELETE" }` → status `DELETION_PENDING`,
  `deletion_requested_at`, revoke all sessions/devices, emit events; a daily job hard-deletes after
  `ACCOUNT_DELETION_GRACE_DAYS` (default 7): videos, metadata, playback, downloads mirror, storage
  locations, devices, sessions, sync events, notifications, entitlements overrides; payments and
  invoices are **retained** (legal/accounting) with the user reference anonymized. Logging in during
  the grace period offers "Restore account" (`POST /users/me/restore`).
- UI: two-step confirmation explaining consequences and that **Play subscriptions must be
  cancelled in Google Play** (button to `manageUrl`).
- A public web page URL for deletion requests is required for the Play listing — note it for Phase 18.

## 7. Cross-cutting requirements

- **States:** every screen implements Loading (skeleton), Content, Empty, Error (retry), Offline
  (cached content + banner). One `UiState` sealed hierarchy pattern across ViewModels.
- **Accessibility:** content descriptions; 48 dp touch targets; TalkBack order sensible; no
  information by color alone (badges have icons/text); supports font scale 200 % without clipping;
  contrast ≥ 4.5:1. Run Compose accessibility checks in UI tests.
- **Localization ready:** all text in `strings.xml`, plurals, no concatenated sentences;
  add `values-hi/strings.xml` scaffold (can be English placeholders) to prove RTL/LTR-safe layouts.
- **Performance:** cold start < 1.5 s on a mid-range emulator profile; baseline profile generated
  with a Macrobenchmark module (`:benchmark-phone`); stable keys in lazy lists; image sizes
  requested at display size; no work on the main thread (StrictMode in debug).
- **Edge-to-edge** with proper insets; predictive back enabled.

## 8. Tests
- Screenshot tests (Roborazzi or Paparazzi) for every main screen and component: light, dark,
  font scale 1.0 and 2.0, empty and error states.
- ViewModel tests for every new/refactored ViewModel (Turbine).
- UI tests: tab navigation & state restoration; add-video happy path & duplicate; library search +
  filter + sort; multi-select delete with undo; delete-account flow (fake repo); offline banner.
- Backend e2e: account deletion + restore + hard-delete job (FakeClock), category rename, device settings validation.
- Macrobenchmark: startup + library scroll (10k items seeded via a debug-only seeder).

## 9. File structure (new/changed)
```
android/core/designsystem/…/{theme/*,components/*}
android/app-phone/…/{navigation/*,onboarding/*,home/*,library/*,add/*,detail/*,downloads/*,devices/*,settings/**}
android/benchmark-phone/
android/app-phone/src/main/res/values-hi/strings.xml
backend/src/modules/users/{account-deletion.service.ts,account-deletion.cron.ts}
backend/src/modules/videos/categories.controller.ts
backend/src/modules/devices/device-settings.dto.ts
```

## 10. Acceptance criteria
- [ ] Five destinations with preserved state; tablets use a navigation rail.
- [ ] Home shows continue watching, recently added, TV downloads, TVs, limit banners — or a helpful empty state.
- [ ] Library handles 10k items smoothly (benchmark), with thumbnails, badges, search, filters, sort, multi-select.
- [ ] Add Video previews compatibility before saving; Share flow matches the design.
- [ ] Downloads screen mirrors TV downloads live.
- [ ] Settings complete, including working account deletion (+ restore within grace) and Play subscription warning.
- [ ] Every screen has loading/empty/error/offline states (screenshot evidence).
- [ ] Accessibility checks pass; 200 % font scale usable; dark theme correct.
- [ ] All tests pass; baseline profile committed.

## 11. Manual test script
1. Fresh install → onboarding → sign in → empty Home with guidance.
2. Share 3 links from WhatsApp/Chrome → Home "Recently added" updates; open Library → filter by "Playable".
3. Toggle airplane mode → offline banner; add a link → appears with "Waiting to sync" → reconnect → synced.
4. Start a download on the TV → phone Downloads shows live progress and speed.
5. Settings → Appearance Dark → all screens readable; font size max → no clipping.
6. Delete account → confirm → signed out on phone and TV → sign in again within 7 days → Restore.

## 12. Pitfalls
- Paging + Room + search: use FTS for queries; don't filter 10k rows in Kotlin.
- Coil: set memory/disk cache sizes; many og:images are huge — request sized thumbnails.
- Avoid a second source of truth: screens read Room flows; network refresh only writes to Room.
