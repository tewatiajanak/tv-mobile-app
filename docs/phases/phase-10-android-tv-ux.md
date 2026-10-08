# Phase 10 — Android TV UX (True Ten-Foot Interface)

> **Implement this phase only. Do not start Phase 11. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-10-android-tv-ux.md
and the latest phase report. We are implementing Phase 10 (Android TV UX) only.
Inspect every TV screen, the tv-designsystem module, the player and download UI. Give me a numbered
plan: TV design tokens and focus components, navigation shell, Home rows, Library, Video detail
(PLAY/DOWNLOAD/QUEUE/DELETE), Downloads, Settings (storage, pairing, account, downloads, playback,
subscription), first-run flow, player chrome, Watch Next integration, focus management rules,
performance on low-end TVs, and D-pad UI/screenshot tests. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 10 step by step: tokens/components → shell → each screen with D-pad tests →
player chrome → Watch Next → performance pass. Test every screen with the D-pad only (no touch,
no mouse). Build, test and commit after each screen.
```

**Prompt C — verify & report**
```
Run all tests and TV screenshot tests (1080p and 720p). Walk the manual D-pad script on the TV
emulator (and a real TV if attached), measure startup time and row-scroll jank, and attach screenshots
to the report. Tick acceptance criteria, write docs/phase-reports/PHASE-10-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<describe the focus/navigation/UI issue>. Root-cause first, smallest fix, add a D-pad test reproducing it, commit.
```

---

## 1. Goal

A TV app designed for the couch: readable from 3 m, operable with **D-pad, Select and Back**
only, with **exceptionally clear focus**, fast on low-end TVs, and organised around what people
do on a TV: continue watching, see what's new, follow downloads, play.

## 2. Prerequisites
Phases 3–8 (pairing, library sync, metadata/player, download engine incl. progressive playback, entitlements).

## 3. Scope
**In:** TV design system, navigation, Home rows, Library, Video detail actions, Downloads,
Settings (storage selection, pairing/account, downloads, playback, subscription), first-run
flow, player chrome polish, system Watch Next row, focus/performance work.
**Out:** download priorities/schedules/bandwidth controls (12 — placeholders hidden), notifications (14).

---

## 4. Ten-foot design rules (enforce in code review)

| Rule | Value |
|---|---|
| Safe area (overscan) | 48 dp left/right, 27 dp top/bottom on every screen |
| Minimum text | body 18 sp; secondary 16 sp only for metadata; titles 28–40 sp |
| Focus indication | scale 1.06–1.10 + 3 dp high-contrast border + elevated shadow; **never** color-only |
| Focus visibility | the focused element is always on screen; rows scroll to keep focus ~1/3 from the left |
| Contrast | ≥ 4.5:1 for text, ≥ 3:1 for focus ring against both card and background |
| Card sizes | 16:9 cards 268×151 dp (rows), hero area max 40 % of screen height |
| Motion | short (150–250 ms), no parallax that moves focused content unpredictably |
| Back | always goes up one level; on Home closes menus then exits (no confirmation dialog) |
| No touch assumptions | no swipe, long text fields minimized, no tiny icon-only buttons without labels |
| Theme | dark by default (TV rooms are dark), no pure white backgrounds |

`core:tv-designsystem` provides: `VbTvTheme`, `FocusableCard` (16:9 thumbnail, title, subtitle,
progress bar, badge, download chip), `TvButton` (primary/secondary/destructive), `TvChip`,
`TvRow(title, items, emptyHint)`, `TvDialog` (focus defaults to the safest option),
`TvSideMenu`/navigation drawer item, `TvProgressBar`, `TvEmptyState`, `TvErrorState`,
`TvToast`, `QrCodePanel`. Built on `androidx.tv.material3` + standard Compose `LazyRow/LazyColumn`.

## 5. Focus management rules
- Every screen declares its **initial focus** (`FocusRequester`) — e.g. Home → first card of the
  first non-empty row; Detail → PLAY (or DOWNLOAD if not playable).
- **Restore focus** when returning (`Modifier.focusRestorer()` per row + remembered item key);
  returning from Detail lands on the same card.
- Live data updates (sync, progress) must **not** move focus: stable `key`s in lazy lists, no
  item removal under focus without moving focus to a neighbour first.
- Focus groups: rows are focus groups; Up/Down moves between rows keeping the column position
  where possible; Left at the first item opens the side menu.
- No focus traps: every dialog/menu is closable with Back; tested.
- Long-press Select opens a context menu on cards (Play, Download, Queue, Delete, Details).

## 6. Screens

### 6.1 Navigation shell
Left **navigation drawer** (collapsed icons, expands on focus): Home, Library, Downloads, Search,
Settings; account avatar at the bottom. Content area to the right. Drawer state doesn't steal
focus on startup.

### 6.2 Home
Optional hero (most recent playable item, backdrop = thumbnail with a dark gradient, title, PLAY
button). Rows, each hidden when empty:
1. **Continue Watching** — progress bars, sorted by last played (from playback positions).
2. **Recently Added** — newest 20, "New" badge for items added in the last 24 h.
3. **Downloading** — live progress, speed, Watch-now indicator (Phase 7).
4. **Downloaded** — completed local files (badge with storage label; greyed with "Storage not
   connected" when the USB drive is absent).
5. **My Library** — by category (one row per top category, up to 6 rows) + "All videos" card.
Empty state for a brand-new account: big QR "Add videos from your phone" (deep link to phone
Add screen) and text "Share any video link to VideoBridge on your phone — it appears here instantly."

### 6.3 Library
Grid (5 columns at 1080p) with filter chips row (All, Playable, Downloaded, Category…) and sort
(Newest, A–Z, Recently watched). Search screen with the system TV keyboard + voice input if
available (`RecognizerIntent` when supported) + recent searches.

### 6.4 Video detail
Backdrop + metadata (title, domain, type badge, size, duration, added by/when, notes, category),
download status per storage location, playback progress. Action row:

| Button | Behaviour |
|---|---|
| **PLAY** / **RESUME** | plays remote URL, or the local file when downloaded (prefer local), or Watch-now when progressive-ready |
| **DOWNLOAD** | enqueue with priority *High* (starts next); storage picker if no default; disabled with reason when not downloadable |
| **QUEUE** | enqueue at *Normal* priority at the end of the queue |
| **DELETE** | dialog: "Delete from library" / "Delete downloaded file only" / Cancel (focus on Cancel) |
State-aware variants: Downloading → PAUSE / CANCEL / WATCH NOW; Downloaded → PLAY / DELETE FILE;
Failed → RETRY / CHANGE LOCATION. The `priority` field already exists (Phase 6); Phase 12 formalises ordering.

### 6.5 Downloads
Tabs or sections: Active, Queued, Completed, Failed. Rows with large progress bars, `1.2 / 3.4 GB ·
4.5 MB/s · 8 min left`, plain-language reasons ("Waiting for USB drive", "Waiting for network",
"Link expired — ask the sender for a new link"), actions as focusable buttons. Storage summary
header: each location with free space bar and availability.

### 6.6 Settings
| Section | Content |
|---|---|
| Storage | locations (add via SAF/fallback, set default, remove, re-grant access, free space, read-only/FAT32 warnings) |
| Pairing & account | signed-in phone number (masked), this TV's name (rename), "Pair a different account" (sign out → pairing screen), sign out |
| Downloads | auto-resume on start (on), keep screen awake while downloading (off), show Watch-now (on); Phase 12 items hidden |
| Playback | resume behaviour (Ask/Always/Start over), **match content frame rate** (Media3 `setVideoChangeFrameRateStrategy`), default audio/subtitle language, subtitle size |
| Subscription | plan, status, usage, "Upgrade from your phone" QR |
| About | version, device id (short), support QR (mailto/web), licenses, privacy/terms QR links |
Each setting row is a focusable list item with value on the right; toggles change with Select.

### 6.7 First-run flow (after pairing)
1. "Choose where to save downloads" (skippable; explains USB/HDD; launches storage flow).
2. "You're all set" — shows how to add videos from the phone (QR to phone Add screen).

### 6.8 Player chrome (refine Phase 5/7)
Title + source at top, large seek bar with downloaded-range track, time labels, buttons row
(Play/Pause, −10/+10, Audio & subtitles, Info); thumbnails on seek are optional. Auto-hide 4 s.
"Up next" isn't required.

### 6.9 System integration
- **Watch Next** (Android TV home "Continue watching" row): publish/update/remove
  `WatchNextProgram` entries via `androidx.tvprovider` for videos with progress (type
  `WATCH_NEXT_TYPE_CONTINUE`, last engagement time, position/duration, poster = thumbnail, intent
  deep link `videobridge://tv/video/{id}?resume=true`). Remove when completed or deleted. Guard
  with a capability check (not all launchers show it; Google TV may require the Engage SDK in
  future — document what you observe).
- Launcher banner (320×180 xhdpi), app icon, `android:label`; `android:isGame=false`.
- Media session already in place (Phase 5): verify remote play/pause and Assistant voice commands.

## 7. Performance on low-end TVs
- Target devices: 1–2 GB RAM, quad-core A53. Test with the Android TV emulator at 1080p and,
  ideally, a cheap real TV/stick.
- Cold start to interactive Home < 2 s (emulator) with cached data; Home renders from Room first.
- Images: Coil with sizes matching card dp × density; memory cache ≤ 15 % of app memory; disk cache 100 MB.
- Avoid `blur` and large alpha layers; precompute gradients; no recomposition storms from 1 s
  progress ticks (collect progress per card with `derivedStateOf`/keys).
- JankStats on row scrolling in debug; baseline profile via a TV macrobenchmark module (`:benchmark-tv`).

## 8. Tests
- **D-pad UI tests** (Compose test with key events `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT/CENTER`, `BACK`):
  initial focus per screen; row-to-row navigation keeps column; focus restored after Detail → Back;
  side menu open/close; every dialog closable by Back with safe default focus; live progress update
  doesn't move focus; long-press context menu.
- Screenshot tests at 1920×1080 and 1280×720 (tv density), focused and unfocused card states, empty states.
- ViewModel tests for new screens; Watch Next publisher test (fake content resolver).
- Macrobenchmark: startup + Home row scroll.

## 9. File structure (new/changed)
```
android/core/tv-designsystem/…/{theme/*,components/*,focus/*}
android/app-tv/…/{shell/*,home/*,library/*,search/*,detail/*,downloads/*,settings/**,firstrun/*,player/* (chrome),watchnext/WatchNextPublisher.kt}
android/benchmark-tv/
android/app-tv/src/main/res/{drawable-xhdpi/tv_banner.png,mipmap-*/ic_launcher*}
```

## 10. Acceptance criteria
- [ ] Every screen fully usable with D-pad/Select/Back only; no focus traps; focus always visible and obvious from 3 m.
- [ ] Home rows: Continue Watching, Recently Added, Downloading, Downloaded, My Library — live and correct; empty-state guidance.
- [ ] Detail actions PLAY/DOWNLOAD/QUEUE/DELETE (+ state variants) work and show reasons when disabled.
- [ ] Settings cover storage selection, pairing/account, downloads, playback (incl. frame-rate matching), subscription.
- [ ] Focus restoration and stability under live updates verified by tests.
- [ ] Watch Next entries appear/update/remove on the Android TV home (where supported; documented otherwise).
- [ ] Safe-area, text-size and contrast rules met (screenshot evidence).
- [ ] Startup < 2 s on emulator; smooth row scrolling; baseline profile committed.
- [ ] All tests pass.

## 11. Manual test script (D-pad only)
1. Launch → Home → Right through Continue Watching → Down to Recently Added (column kept) → Select → Detail with PLAY focused → Back → same card focused.
2. Long-press a card → context menu → Queue → Downloads shows it queued.
3. While a download progresses on the focused card's row, keep pressing Right — focus never jumps.
4. Settings → Storage → add USB → set default → Detail → DOWNLOAD uses it without asking.
5. Unplug USB (or `sm unmount`) → Downloaded row greys the item with "Storage not connected".
6. Play a video, watch 2 min, Home button → Android TV home shows it in "Continue watching" (if supported) → open → resumes.

## 12. Pitfalls
- `androidx.tv` lazy APIs (`TvLazyRow` etc.) are deprecated — use standard Compose lazy layouts with `focusRestorer`.
- Default Compose focus search can skip items in irregular grids — specify `focusProperties` where needed.
- Some TVs send `KEYCODE_ENTER`/`KEYCODE_NUMPAD_ENTER` instead of `DPAD_CENTER` — handle all three.
- Real TV overscan varies — keep the safe area even if the emulator looks fine without it.
