# Phase 5 — Video Metadata, Compatibility & Playback

> **Implement this phase only. Do not start Phase 6. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-05-metadata-compatibility-playback.md
and the latest phase report. We are implementing Phase 5 (Video Metadata & Compatibility + Playback) only.
Inspect the videos/sync modules and core:player first. Give me a numbered plan: media fixture
server, SafeHttpClient with SSRF protection, classifier, inspection queue, metadata/playback
schema + endpoints, TV-side local inspection fallback, Media3 player on TV (and basic phone
player), playback-position persistence and sync, and the tests for each. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 5 step by step. Start with the fixture server and SafeHttpClient and its
SSRF test suite — nothing else may make outbound HTTP calls. Then classifier → queue → endpoints →
events → Android player → position sync → tests. Build, test and commit after each step.
```

**Prompt C — verify & report**
```
Run the Phase 5 verification commands, the SSRF test suite and all tests. Show classification
results for every fixture URL. Play an MP4 and an HLS fixture on the TV emulator, stop midway,
reopen and resume. Tick acceptance criteria with evidence, write docs/phase-reports/PHASE-05-report.md,
update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

When a link is saved, the backend **safely** inspects it (headers or a few KB only) and labels
it `DIRECT_MEDIA`, `SUPPORTED_STREAM`, `WEB_PAGE`, `UNKNOWN` or `UNSUPPORTED`, with size, type,
range support, filename and a human reason. The TV **plays** supported media with Media3
(buffering, seeking, pause/resume, fullscreen) and **remembers the position** across devices.

## 2. Prerequisites
Phase 4: videos, sync events, realtime, TV library/detail with hidden Play button.

## 3. Scope
**In:** SSRF-safe HTTP client, URL classifier, async inspection queue, `video_metadata`,
`playback_positions`, inspect/preview/metadata/playback APIs, `VIDEO_METADATA_UPDATED` and
`PLAYBACK_UPDATED` events, TV local inspection for LAN/IP-bound URLs, TV player, basic phone
player, continue-watching data.
**Out:** downloads (6), progressive playback of downloads (7), polished player UI (10).

---

## 4. Media fixture server (build this first)

`tools/media-fixtures/` — a tiny Node (or nginx) server started by `docker compose -f infra/docker-compose.test.yml`
and usable from emulators at `http://10.0.2.2:8090`:

| Path | Content |
|---|---|
| `/mp4/faststart.mp4` | 60 s 720p H.264/AAC, `moov` at start (`ffmpeg -movflags +faststart`) |
| `/mp4/moov-at-end.mp4` | same, `moov` at end |
| `/mkv/sample.mkv`, `/webm/sample.webm`, `/ts/sample.ts` | other containers |
| `/hls/index.m3u8` | clear VOD HLS (3 renditions) |
| `/hls-aes/index.m3u8` | HLS with `EXT-X-KEY:METHOD=AES-128` (standard, playable) |
| `/hls-drm/index.m3u8` | playlist with `METHOD=SAMPLE-AES` + Widevine `KEYFORMAT` (must be UNSUPPORTED) |
| `/dash/manifest.mpd`, `/dash-drm/manifest.mpd` | clear DASH / DASH with `<ContentProtection>` |
| `/page.html` | HTML with `og:title`, `og:image` |
| `/redirect/1` → `/redirect/2` → mp4 | redirect chain |
| `/redirect/to-private` | 302 to `http://127.0.0.1/…` (must be blocked) |
| `/no-range.mp4` | ignores `Range` (always 200) |
| `/slow.mp4` | throttled to 200 KB/s (Phases 6–7) |
| `/flaky.mp4` | drops the connection every N MB (Phase 6) |
| `/expiring.mp4?exp=…` | 403 after expiry (signed-URL simulation) |

Generate media with `tools/media-fixtures/make-fixtures.sh` (ffmpeg `testsrc`/`sine`); commit the
script, not large binaries (CI generates them; cache them).

---

## 5. SafeHttpClient (`backend/src/common/net/safe-http/`)

**The only component allowed to make outbound HTTP requests to user-supplied URLs.**
Built on `undici` with a custom `connect` / `lookup`:

1. Scheme must be `http:`/`https:`. Port must be in `INSPECTION_ALLOWED_PORTS` (default `80,443,8080,8443`).
2. Reject hostnames that are IP literals in blocked ranges, `localhost`, `*.localhost`,
   `*.local`, `*.internal`, and single-label hosts.
3. Resolve DNS **once** (A + AAAA); reject if **any** resolved address is blocked; then connect
   to the validated IP (pinning) while sending the original `Host`/SNI — defeats DNS rebinding.
4. Blocked ranges (IPv4): `0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16,
   172.16.0.0/12, 192.0.0.0/24, 192.0.2.0/24, 192.88.99.0/24, 192.168.0.0/16, 198.18.0.0/15,
   198.51.100.0/24, 203.0.113.0/24, 224.0.0.0/4, 240.0.0.0/4, 255.255.255.255/32`.
   (IPv6): `::/128, ::1/128, ::ffff:0:0/96` (check the embedded IPv4), `64:ff9b::/96` (check embedded),
   `100::/64, 2001:db8::/32, fc00::/7, fe80::/10, ff00::/8`, plus `2002::/16` (6to4 → check embedded).
   Use `ipaddr.js` for parsing; normalize odd IPv4 forms (`0x7f.1`, `2130706433`, `127.1`).
5. Redirects handled **manually**, max 5; every hop re-runs steps 1–4; `https → http` downgrade
   allowed only if config allows (default deny); record the chain.
6. Timeouts: DNS 2 s, connect 3 s, headers 5 s, whole request 10 s. Response body cap per call
   (0 for media, 64 KB for HTML, 256 KB for manifests); abort the socket after the cap.
7. Request headers: `User-Agent: VideoBridgeInspector/1.0 (+https://<domain>/bot)`,
   `Accept: */*`, no cookies, no auth forwarding. Max 3 concurrent requests per destination host.
8. Test-only escape hatch: `INSPECTION_ALLOW_PRIVATE_HOSTS` (e.g. `127.0.0.1:8090`) — boot fails
   if set when `APP_ENV=production`.

**SSRF test suite** (unit + e2e, table-driven, ≥ 40 cases): every blocked range, IPv6-mapped,
decimal/octal/hex IPv4, `localhost.`, trailing dots, DNS that returns one public + one private
address, redirect to private, redirect loop, redirect to `file:`/`gopher:`/`ftp:`, port 22/6379/5432,
cloud metadata `169.254.169.254` and `fd00:ec2::254`, credentials in URL, very long URL, body
larger than cap, slow-loris server (timeout).

---

## 6. Classifier (`modules/metadata/classifier.ts`)

Inspection procedure:
1. `HEAD` the URL. If 405/403/501 or missing `Content-Type`, retry with `GET` + `Range: bytes=0-0`
   (body cap 0 — read nothing).
2. Range support: `Accept-Ranges: bytes` **or** a `206` with `Content-Range: bytes 0-0/<total>`.
   Total size from `Content-Range` or `Content-Length` (of a 200).
3. Filename from `Content-Disposition` (RFC 6266, `filename*` first), else the URL path.
4. Decide:

| Signal | Classification | playable | downloadable |
|---|---|---|---|
| Host in the **platform denylist** (config, e.g. youtube.com, youtu.be, netflix.com, primevideo.com, hotstar.com, jiocinema.com, instagram.com, facebook.com, x.com) | `UNSUPPORTED` reason `PLATFORM_NOT_SUPPORTED` | no | no |
| `video/*`, `audio/*`, or `application/octet-stream`/`binary/octet-stream` + media extension (`mp4 m4v mov mkv webm ts m2ts avi mp3 m4a aac flac`) | `DIRECT_MEDIA` | yes (container known) | yes |
| `application/vnd.apple.mpegurl`, `application/x-mpegurl`, `audio/mpegurl` or `.m3u8` | fetch ≤ 256 KB, parse | | |
| ↳ contains `METHOD=SAMPLE-AES`, `SAMPLE-AES-CTR`, or `KEYFORMAT` Widevine/PlayReady/FairPlay | `UNSUPPORTED` reason `DRM_PROTECTED` | no | no |
| ↳ otherwise (clear or `AES-128`) | `SUPPORTED_STREAM` (`streamType: HLS`, `isLive` if no `#EXT-X-ENDLIST`, duration = Σ`EXTINF` of first media playlist if VOD) | yes | no (stream download is out of scope) |
| `application/dash+xml` or `.mpd` → contains `<ContentProtection` | `UNSUPPORTED` `DRM_PROTECTED` | no | no |
| ↳ otherwise | `SUPPORTED_STREAM` (`DASH`) | yes | no |
| `text/html` | `WEB_PAGE` — read ≤ 64 KB, extract `og:title`, `og:image`, `og:description`, `<title>` for display only. **Do not** extract or follow embedded video URLs. Reason `WEB_PAGE_NOT_MEDIA` | no | no |
| 401/403 | `UNKNOWN` reason `ACCESS_DENIED` (may be IP-bound/signed → TV fallback) | | |
| 404/410 | `UNSUPPORTED` reason `NOT_FOUND` → video status `UNREACHABLE` | | |
| blocked by SSRF rules (private IP etc.) | `UNKNOWN` reason `PRIVATE_NETWORK` → TV fallback | | |
| timeout / DNS failure / 5xx | `UNKNOWN` reason `UNREACHABLE` (retry later with backoff, max 3) | | |
| anything else | `UNKNOWN` reason `UNRECOGNIZED_TYPE` | | |

Video status mapping: `READY` (DIRECT_MEDIA / SUPPORTED_STREAM), `UNSUPPORTED`
(UNSUPPORTED / WEB_PAGE), `UNREACHABLE` (NOT_FOUND or UNREACHABLE after retries), else stays
`NEW` with metadata `UNKNOWN`.

> Codec reality: a supported **container** doesn't guarantee the TV has a **decoder** (HEVC,
> AC-3/E-AC-3/DTS audio, 10-bit, 4K on low-end TVs). Classification says "likely playable";
> the player reports real decoder errors (Section 9) and the TV caches `decoderUnsupported` per
> video. The FFmpeg extension is **not** bundled (licensing/size) — document this.

Human-readable reasons live in one map used by API (`reasonMessage`) and mirrored in Android strings.

---

## 7. Data model (migration `…_metadata_playback`)

```prisma
enum MediaClassification { DIRECT_MEDIA SUPPORTED_STREAM WEB_PAGE UNKNOWN UNSUPPORTED }
enum InspectedBy { SERVER DEVICE }
enum TitleSource { USER DEFAULT METADATA }

model VideoMetadata {
  videoId           String              @id @map("video_id") @db.Uuid
  userId            String              @map("user_id") @db.Uuid
  classification    MediaClassification
  reasonCode        String?             @map("reason_code") @db.VarChar(40)
  playable          Boolean             @default(false)
  downloadable      Boolean             @default(false)
  contentType       String?             @map("content_type") @db.VarChar(120)
  contentLengthBytes BigInt?            @map("content_length_bytes")
  acceptsRanges     Boolean?            @map("accepts_ranges")
  etag              String?             @db.VarChar(200)
  lastModified      String?             @map("last_modified") @db.VarChar(64)
  filename          String?             @db.VarChar(255)
  finalUrl          String?             @map("final_url") @db.VarChar(2048)   // never logged
  redirectCount     Int                 @default(0) @map("redirect_count")
  httpStatus        Int?                @map("http_status")
  streamType        String?             @map("stream_type") @db.VarChar(10)   // HLS | DASH
  isLive            Boolean?            @map("is_live")
  drmDetected       Boolean             @default(false) @map("drm_detected")
  durationMs        BigInt?             @map("duration_ms")
  pageTitle         String?             @map("page_title") @db.VarChar(300)
  pageImageUrl      String?             @map("page_image_url") @db.VarChar(2048)
  inspectedBy       InspectedBy         @map("inspected_by")
  inspectedAt       DateTime            @map("inspected_at") @db.Timestamptz
  attempts          Int                 @default(0)
  nextAttemptAt     DateTime?           @map("next_attempt_at") @db.Timestamptz
  @@index([userId])
  @@map("video_metadata")
}

model PlaybackPosition {
  userId      String   @map("user_id") @db.Uuid
  videoId     String   @map("video_id") @db.Uuid
  positionMs  BigInt   @map("position_ms")
  durationMs  BigInt?  @map("duration_ms")
  completed   Boolean  @default(false)
  deviceId    String?  @map("device_id") @db.Uuid
  updatedAt   DateTime @updatedAt @map("updated_at") @db.Timestamptz
  @@id([userId, videoId])
  @@index([userId, updatedAt(sort: Desc)])
  @@map("playback_positions")
}
```
`videos` gains `title_source TitleSource DEFAULT 'DEFAULT'` (USER when the user typed/edited
the title) and `last_played_at`. The video DTO gains `metadata` (summary: classification,
playable, downloadable, reasonCode, reasonMessage, contentLengthBytes, acceptsRanges, streamType)
and `playback` (`positionMs`, `durationMs`, `completed`, `updatedAt`).

---

## 8. Backend tasks

1. Fixture server + compose service. 2. `SafeHttpClient` + SSRF suite.
3. **Inspection queue**: BullMQ on Redis, queue `inspection`, concurrency 10, per-user limiter
   60 jobs/hour, retries for `UNREACHABLE` (1 min, 10 min, 1 h). Enqueued on video create and on
   `sourceUrl`… (URL is immutable after create; to change URL the user creates a new video —
   document this).
4. `InspectionService.inspect(videoId)`: status `INSPECTING` (+ event), run classifier, upsert
   `video_metadata`, update video (`status`, `mimeType`, `fileSizeBytes`, `durationMs`,
   `thumbnailUrl` from `og:image` if empty, `title` from filename/`og:title` **only if**
   `titleSource != USER`) — all through `SyncService.recordChange` → `VIDEO_UPDATED` +
   `VIDEO_METADATA_UPDATED`.
5. Endpoints:

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/videos/{id}/metadata` | full metadata (without `finalUrl` query string) |
| POST | `/api/v1/videos/{id}/inspect` | re-inspect; 202; limit 10/hour per video, 60/hour per user |
| POST | `/api/v1/metadata/preview` | `{ url }` → classification preview for the Add screen (sync, ≤ 10 s); 30/hour per user; nothing stored |
| PUT | `/api/v1/videos/{id}/metadata/device-report` | TV fallback result `{ classification, contentType, contentLengthBytes, acceptsRanges, filename, httpStatus }`; accepted only when server result is `UNKNOWN` with reason `PRIVATE_NETWORK` or `ACCESS_DENIED`; `inspectedBy = DEVICE`; values validated and clamped |
| PUT | `/api/v1/playback/{videoId}` | `{ positionMs, durationMs?, completed?, final: bool }` → 200; last-write-wins by server receive time; `final=true` (pause/stop/complete) records a `PLAYBACK_UPDATED` sync event, periodic saves don't (prevents event spam). Also fills `videos.duration_ms` if null. |
| GET | `/api/v1/playback?limit=20` | continue-watching list: not completed, position > 0, sorted by `updatedAt` desc |

6. OpenAPI, `docs/architecture.md` "Inspection & SSRF", `docs/device-compatibility.md` codec notes.

### Backend tests
- Classifier table tests against the fixture server (each row in Section 6).
- SSRF suite (Section 5).
- Inspection job: title not overwritten when `titleSource=USER`; events emitted; retries/backoff.
- Device report accepted only in the allowed states and only for own videos.
- Playback: LWW, `final` creates event, continue-watching ordering, isolation.
- Logs never contain query strings of inspected URLs.

---

## 9. Android tasks

### 9.1 Data
- Room: `VideoMetadataEntity`, `PlaybackPositionEntity` (+ migration + test). Sync applies
  `VIDEO_METADATA_UPDATED` / `PLAYBACK_UPDATED`.
- `PlaybackRepository`: `observePosition(videoId)`, `savePosition(videoId, pos, dur, final)`
  — writes Room immediately; network save throttled (every 15 s while playing) + `final` saves
  queued through WorkManager if offline.

### 9.2 `core:player`
- `PlayerFactory.create(context)`: `ExoPlayer` with `DefaultMediaSourceFactory` using an
  **OkHttp data source** (shared client, User-Agent `VideoBridge/<ver> (Android TV)`, no auth headers
  to third parties, cross-protocol redirects allowed), `DefaultLoadControl` tuned for TV
  (min buffer 15 s, max 50 s, playback start 2.5 s, rebuffer 5 s), `setHandleAudioBecomingNoisy(true)`,
  audio attributes for movies with focus handling, `C.WAKE_MODE_NETWORK`.
- `MediaItemFactory`: maps metadata → `MediaItem` with MIME hint (`APPLICATION_M3U8`,
  `APPLICATION_MPD`, or from content type) so ExoPlayer doesn't have to sniff.
- `PlaybackErrorMapper`: `PlaybackException.errorCode` → user messages: `ERROR_CODE_IO_BAD_HTTP_STATUS` (403 → "Link expired or access denied", 404 → "Video no longer available"),
  `ERROR_CODE_IO_NETWORK_CONNECTION_*` → "Network problem — retrying",
  `ERROR_CODE_DECODER_INIT_FAILED / DECODING_FORMAT_UNSUPPORTED / DECODING_FORMAT_EXCEEDS_CAPABILITIES` → "This TV can't decode this video's format" (+ record `decoderUnsupported`),
  `ERROR_CODE_PARSING_*` → "Unsupported or damaged file", `DRM_*` → "Protected content isn't supported".
- `MediaSession` (media3-session) so remote media keys, Google Assistant ("pause") and the
  system "now playing" work.

### 9.3 TV player (`app-tv/player/`)
- `TvPlayerScreen`: full screen, `PlayerView` (in `AndroidView`) with controller auto-hide 4 s,
  buffering spinner, title overlay; `keepScreenOn`.
- Remote keys: Center/`MEDIA_PLAY_PAUSE` toggle; Left/Right seek −10 s/+10 s (hold → accelerate
  to ±60 s); `MEDIA_FAST_FORWARD/REWIND` ±30 s; Up shows controls/track selection (audio &
  subtitle tracks when present); Back hides controls first, then exits.
- Resume: if position > 30 s and not completed → dialog "Resume from 12:34" / "Start over"
  (focus on Resume).
- Save position: every 15 s while playing (non-final), on pause/stop/back/`onStop` (final),
  `completed = true` when ≥ 95 % or < 30 s remaining.
- Live HLS: seek bar hidden or live-edge aware; no resume prompt.
- Error screen with Retry and Back; decoder errors offer "Try download instead" only if downloadable (enabled in Phase 6).

### 9.4 TV local inspection fallback
- `LocalInspector` (TV): same classification logic in Kotlin (HEAD / Range 0-0 with OkHttp,
  5 s timeouts, **body never read** except ≤ 256 KB for manifests). Runs when metadata says
  `UNKNOWN` + `PRIVATE_NETWORK`/`ACCESS_DENIED` and the TV opens the detail screen; reports via
  `device-report`. This is how home NAS links (`http://192.168.1.10/movie.mp4`) work — the TV
  is on that network, the cloud isn't.

### 9.5 Detail screens
- TV detail: shows classification badge ("Playable", "Stream", "Web page — not a video file",
  "Protected — not supported", "Checking…"), size, type, reason message; Play enabled only when
  playable (or UNKNOWN → "Try to play"). Re-check button (calls `/inspect`).
- Phone: Add screen calls `/metadata/preview` after the URL is entered (debounced 600 ms) and
  shows the badge before saving. Phone detail shows the same info + **Play on phone** (basic
  player using the same `core:player`, portrait/landscape, position saving).

### 9.6 Android tests
- `PlaybackErrorMapperTest`, `MediaItemFactoryTest`, `LocalInspectorTest` (MockWebServer: HEAD
  unsupported → Range GET; 206 parsing; DRM HLS detection), `PlaybackRepositoryTest` (throttling, final save offline → WorkManager).
- Instrumented (TV emulator, fixture server): play `/mp4/faststart.mp4` for 5 s → position saved;
  relaunch → resume dialog.

---

## 10. File structure (new/changed)
```
tools/media-fixtures/{server.mjs,make-fixtures.sh,Dockerfile,README.md}
backend/src/common/net/safe-http/{safe-http.client.ts,ip-policy.ts,dns-pinning.ts,redirects.ts,*.spec.ts}
backend/src/modules/metadata/{metadata.module.ts,classifier.ts,hls.ts,dash.ts,html-meta.ts,content-disposition.ts,platform-denylist.ts,inspection.queue.ts,inspection.service.ts,metadata.controller.ts,reasons.ts}
backend/src/modules/playback/{playback.module.ts,playback.service.ts,playback.controller.ts,dto/*}
backend/test/{ssrf.e2e-spec.ts,inspection.e2e-spec.ts,playback.e2e-spec.ts}
android/core/player/…/{PlayerFactory.kt,MediaItemFactory.kt,PlaybackErrorMapper.kt,PlaybackSessionService.kt}
android/core/data/…/{PlaybackRepository.kt,MetadataRepository.kt,LocalInspector.kt}
android/app-tv/…/player/{TvPlayerScreen.kt,TvPlayerViewModel.kt,RemoteKeyHandler.kt,ResumeDialog.kt}
android/app-phone/…/player/{PhonePlayerScreen.kt,PhonePlayerViewModel.kt}
```

## 11. Security requirements
- Only `SafeHttpClient` may fetch user URLs (add an ESLint `no-restricted-imports` rule banning
  `undici`/`axios`/`node-fetch`/`http` imports outside `common/net/`).
- DNS pinning, redirect re-validation, body caps, timeouts, per-host and per-user limits.
- Never forward cookies/credentials; never store page bodies; never extract embedded videos
  from web pages; DRM content always `UNSUPPORTED`.
- Device reports can't upgrade server-confirmed `UNSUPPORTED`; values clamped/validated.
- `finalUrl` and query strings never logged; API returns them only to the owning user.

## 12. Verification commands
```bash
docker compose -f infra/docker-compose.test.yml up -d media-fixtures
make test
for u in mp4/faststart.mp4 hls/index.m3u8 hls-drm/index.m3u8 dash-drm/manifest.mpd page.html redirect/to-private; do
  curl -s -XPOST localhost:3000/api/v1/metadata/preview -H "authorization: Bearer $AT" -H 'content-type: application/json' \
   -d "{\"url\":\"http://127.0.0.1:8090/$u\"}" | jq -c '{u:"'$u'",c:.classification,r:.reasonCode,p:.playable,d:.downloadable}'
done
curl -s -XPOST localhost:3000/api/v1/metadata/preview -H "authorization: Bearer $AT" -H 'content-type: application/json' -d '{"url":"http://169.254.169.254/latest/meta-data/"}' | jq
```
(`INSPECTION_ALLOW_PRIVATE_HOSTS=127.0.0.1:8090` in dev/test only.)

## 13. Acceptance criteria
- [ ] Every fixture is classified exactly as in Section 6; DRM streams and web pages never playable/downloadable.
- [ ] SSRF suite passes (≥ 40 cases) including DNS rebinding and redirect-to-private.
- [ ] Inspection never downloads more than the configured caps (asserted via fixture server byte counters).
- [ ] Inspection runs async after save; phone and TV update live (`Checking…` → result).
- [ ] User-set titles never overwritten; filename/og titles applied otherwise.
- [ ] LAN/IP-bound URLs get classified by the TV fallback.
- [ ] TV plays MP4/MKV/WebM/TS/HLS/AES-128 HLS/DASH fixtures with buffering, seek, pause/resume, media keys, track selection.
- [ ] Decoder/HTTP/expired-link errors show clear messages.
- [ ] Position saved and resumed on the same TV and across devices (phone ↔ TV); continue-watching list API works.
- [ ] All tests pass; OpenAPI + docs updated.

## 14. Manual test script
1. Save `http://10.0.2.2:8090/mp4/faststart.mp4` on the phone → badge "Playable · 12 MB" → TV detail → Play → seek with D-pad → Back at 0:40.
2. Open on the phone → resume from 0:40.
3. Save the DRM HLS fixture → "Protected — not supported", Play disabled.
4. Save a YouTube link → "This site doesn't allow playback in other apps".
5. Real TV: save a file URL from a NAS on your LAN → TV classifies it → plays.
6. Save `/expiring.mp4` with a short expiry, wait, play → "Link expired or access denied".

## 15. Pitfalls
- `undici` custom lookup must be applied to **every** hop — implement redirects yourself
  (`maxRedirections: 0`).
- Some servers return `200` + full body to `Range` requests — the cap must abort the stream.
- HEAD responses frequently lie (wrong `Content-Type`); trust `GET Range 0-0` when they disagree.
- Signed CDN URLs may be bound to the requester's IP — server sees 403 while the TV works → fallback path.
- `PlayerView` inside Compose: release the player in `onDispose` and on `ON_STOP`; never keep
  two players alive on low-RAM TVs (1–1.5 GB RAM is common).
