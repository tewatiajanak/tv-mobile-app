# Phases 04 + 05 — Saved Links & Playback (core only) — Report

**Date:** 2026-10-08  **Branch / commits:** none — uncommitted working tree.

**Deliberately cut down.** The owner asked for the main flow first and as fast as possible:
save a link on the phone → see it on the TV → play it. Only that was built. Most of what the
two phase files specify is **not** built and is listed in §10.

> **Addendum 2026-10-09 — owner's round of changes, and a first version of downloads**
> - **Seen working on the real devices:** a video the owner saved on the phone ("SpiderMan") is
>   listed on the TV, was played there, and both devices show "Watched 28%". The TV had been
>   connected from the phone with the 4-digit code. So save → list on TV → play → resume point
>   shared is confirmed on hardware. Everything below is new and **not** yet seen working on hardware.
> - Phone add form: **Name first and required**, then the link. Cards show the name, never the link.
> - Cards (phone and TV): coloured preview tile with a play icon and format badge, name, file
>   size, watch progress, download state. **Size and format** are read by the saving device from
>   the video's own server (headers only) and stored with the video; older videos have none.
> - **Search** by name on both (TV uses the in-app keyboard).
> - **Settings** on both: play with Dekho's player or another app; what opening a video does
>   (just play / ask / play and download); where downloads are saved, with free space and
>   whether each drive is connected. TV header: Sign out replaced by search and menu icon
>   buttons; Sign out is inside Settings, with confirmation.
> - **Phone has its own player now** (same Media3 player, resume point saved).
> - **Downloads, first version**, through Android's system download service into the app's own
>   folder on internal storage or a USB drive / memory card: start from the card menu (phone) or
>   by holding OK (TV), progress on the card, play from the file once done, delete the download.
>   This is **not** the Phase 6 engine: no Storage Access Framework (so files live in the app's
>   folder and go away on uninstall), no pause button, no queue or limits, no backend record,
>   and streams (HLS/DASH) cannot be downloaded.
> - **No preview pictures.** Getting a frame from a remote video means downloading part of it
>   on every device; left out for now. Tiles are coloured placeholders.
> - Share → Dekho still saves without asking for a name (it uses the name from the link); the
>   name can't yet be edited in the app.
> - Tests: backend 154 unit, videos e2e 12 of 12; Android 94 unit, lint 0 errors.
> - APKs for these two test devices: `dist/Dekho-phone.apk`, `dist/Dekho-tv.apk`. They are debug
>   builds that talk to the backend on this Mac at `192.168.1.7:3000`; they work nowhere else.

> **Addendum 2 (2026-10-09) — second round after the owner tried it**
> - **Bug fixed (TV):** holding OK opened the options and then played at once. The menu opens
>   while OK is still down, and the release of that same press was taken as "OK on Play". The
>   menu now ignores everything until OK is pressed afresh. Covered by a key-hold test.
> - **Downloads rebuilt as the app's own downloader** (replacing Android's system download
>   service, which cannot pause): HTTP range resume into a `.part` file renamed on completion,
>   run by a WorkManager worker, retried when the network returns. **Stop / Resume / Delete**
>   in the phone's card menu and the TV's hold-OK menu. Downloads started with the previous
>   build are no longer tracked.
> - **Cards** on phone (two-column grid) and TV redone as streaming-style posters: artwork with
>   the name on a dark fade, format badge, red "watched" line, white focus frame on TV.
>   Artwork is the picture the link advertises (a web page's `og:image`, looked up by the saving
>   device) or a coloured backdrop with the initial. **No frame is ever taken from the video.**
> - **TV settings** are a panel sliding in from the right. Choosing "Another video app" lists the
>   video apps installed on the device (also on the phone) and plays through the chosen one.
> - A downloaded file always plays in Dekho's own player (other apps cannot read the app's folder).
> - **Seen on the real devices after this build:** phone shows the poster grid, and a newly saved
>   video shows "MKV" and "2.8 GB" (size/format lookup works); the TV settings panel opens and
>   lists this TV's video apps (Mi TV Video Player, Video player, VLC, and one entry the system
>   labels "None"). **Not yet seen:** an actual download, stop/resume, or the hold-OK fix on hardware.
> - Tests: Android 108 unit (0 failed), lint 0 errors; backend 154 unit, videos e2e 12 of 12.

## 1. Implementation summary
On the phone, Home is now the video library: a **+ Add video link** button (paste a link, optional
title) and a list of saved videos, newest first, each removable. Any app's **Share → Dekho**
saves the first link in the shared text without opening the app and confirms with a toast.
Tapping a video on the phone opens it in whichever video app the phone has. On the TV, Home is a
grid of the same library, refreshed every 4 seconds, with focus on the newest video; OK plays
it full screen with Media3, straight from the source. Where the viewer stopped is saved every
15 seconds and on leaving, shown as "Watched 25%", and used to resume on any device.

## 2. Files created / modified
| Path | Change |
|---|---|
| `backend/src/modules/videos/*`, `src/common/url/normalize-url.ts` | created |
| `backend/prisma/schema.prisma` | `videos` collection |
| `backend/test/videos.e2e-spec.ts` | created |
| `android/core/model/Video.kt`, `core/network/VideosApi.kt`, `core/data/videos/*` | created |
| `android/feature/library/*` | created (view model, share-text parser) |
| `android/app-phone/home/HomeScreen.kt`, `share/ShareReceiverActivity.kt`, manifest | library UI, share target |
| `android/app-tv/home/TvHomeScreen.kt`, `player/TvPlayerScreen.kt` | library grid, player |

## 3. Database changes
New collection `videos` (indexes on `user_id + created_at` and `user_id + url_hash`). The resume
point lives on the video document. Applied to both databases with `npm run prisma:migrate`.

## 4. API changes
| Method | Path | Auth | Change |
|---|---|---|---|
| POST | `/api/v1/videos` | user | new — 201, or 200 for a retried client id |
| GET | `/api/v1/videos` | user | new — whole library, newest first (max 500) |
| PATCH / DELETE | `/api/v1/videos/:id` | user | new — rename / soft delete |
| PUT | `/api/v1/videos/:id/playback` | user | new — resume point |
- New error codes: `URL_NOT_ALLOWED`, `DUPLICATE_VIDEO`. `max_saved_links` (50) enforced.
- OpenAPI regenerated: yes.

## 5. Android changes
- Phone: new exported activity `ShareReceiverActivity` (`ACTION_SEND`, `text/plain`).
- TV: player screen; no new permissions.
- Room schema unchanged: the library is **not** stored on the device.

## 6. Tests and build results
```
backend: lint ✔ typecheck ✔ build ✔   unit → 154 passed   videos e2e → 11 of 11 passed
android: spotlessCheck detekt lintDevDebug testDevDebugUnitTest + both assembles → BUILD SUCCESSFUL
         unit tests → 82 passed, 0 failed;  lint 0 errors
```
On real devices: phone shows the new Home (empty library, + button, menu); TV shows the QR
screen (it is signed out). **Nothing was saved, listed on the TV, or played on a real device**
— the TV has to be connected to the account first, which is the owner's step.

## 7. What works (from the two phase files' criteria)
- [x] Paste a link and save; Share from another app saves; no link → message.
- [x] Duplicates detected after removing tracking parameters; only http/https; no credentials in links.
- [x] Link saved on the phone appears on the TV within ~4 s (polling) — *tests only*.
- [x] Delete from the phone; the TV drops it on its next refresh.
- [x] `max_saved_links` enforced by the backend with a clear message.
- [x] Strict per-user isolation (e2e).
- [x] TV plays with Media3 (MP4, MKV, WebM, TS, HLS, DASH are compiled in), resume across devices — *never run on a device*.
- [x] Player error shows a plain message.

## 8. Manual testing checklist
1. Phone: ⋮ → Connect a TV → enter the TV's 4-digit code → Connect. (Turn off the 1.1.1.1 VPN first.)
2. Phone: + Add video link → paste a direct `.mp4` link → Save. It should appear on the TV within a few seconds.
3. TV: press OK on the video → it plays. Left/Right seek, OK pauses, Back returns.
4. Play 30 seconds, press Back → the card shows "Watched …%"; open again → it resumes.
5. In WhatsApp or Chrome: Share a video link → Dekho → toast "Saved: …".
6. Phone: Remove a video → it disappears from the TV.

## 9. Security notes
- The backend never fetches saved URLs, so there is no SSRF surface yet.
- **The TV plays whatever URL is saved, including `http://` and LAN addresses.** That is the
  point (home NAS links), but cleartext is allowed only in the `dev` flavor today; production
  builds will refuse `http://` videos until that is decided.
- Shared text is treated as untrusted: only the first 20,000 characters are scanned, and links
  are saved, never opened.

## 10. Not built (from Phases 4 and 5)
| Missing | Effect | Planned |
|---|---|---|
| Offline-first phone (Room, outbox) | adding or viewing needs the network | 9 / 11 |
| Sync log, `seq`, WebSocket, change feed | TV polls every 4 s instead of live push | 11 |
| Edit title/category/notes in the app; search, filters, sort | backend can rename; no UI | 9 |
| Version conflicts | last write wins | 11 |
| Delete from the TV; Undo on the phone | phone only, confirm dialog instead of Undo | 10 |
| Link inspection (SSRF-safe client, classifier, "Playable/Web page/Protected" badges) | a web-page or DRM link is saved like any other and simply fails to play | 5 proper |
| Platform denylist (YouTube etc.) | such links fail in the player with the generic message | 5 proper |
| In-app phone player | phone opens links in another app; its position is not saved | 9 |
| Resume dialog, track selection, custom seek steps, media session | default Media3 controls; resumes silently | 10 |
| TV local inspection for LAN links | not needed while nothing is inspected | 5 proper |
| Media fixture server and playback tests on a device | playback is untested on hardware | 16 |
| Downloads | not started | 6 |

## 11. Library versions chosen / changed
None new.

## 12. Next
Phase 6 (TV downloads to internal storage / USB) is the next piece of the main flow. Hosting the
backend online would remove the dependency on this Mac, the LAN and the phone's VPN setting.
