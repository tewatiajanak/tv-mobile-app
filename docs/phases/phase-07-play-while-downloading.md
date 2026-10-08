# Phase 7 — Play While Downloading (Progressive Playback)

> **Implement this phase only. Do not start Phase 8. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-07-play-while-downloading.md
and the latest phase report. We are implementing Phase 7 (Play While Downloading) only.
Inspect tv:download (HttpDownloader, StorageWriter, progress flows) and core:player first.
Give me a numbered plan: container eligibility probe, GrowingFileDataSource, readiness/threshold
and speed-vs-bitrate estimator, seek policy, finalize-while-playing handling, optional hybrid seek
(advanced_playback), TV UI, and tests (JVM + emulator with /slow.mp4). Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 7 step by step: eligibility probe → GrowingFileDataSource (with exhaustive
unit tests for blocking/timeout/EOF/finalize) → readiness estimator → player integration → seek
policy → UI → optional hybrid seek → tests. Build, test and commit after each step.
```

**Prompt C — verify & report**
```
Run all tests. On the TV emulator with the throttled fixture (/slow.mp4 and a faster one),
demonstrate: Watch-now becoming available, playback while downloading, rebuffer when playback
catches up, seek limits, completion during playback, and the disabled state for moov-at-end MP4.
Tick acceptance criteria, write docs/phase-reports/PHASE-07-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure / logcat>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

While a compatible video is downloading, the TV offers **"Watch now"** as soon as enough data
exists. Playback reads the growing `.part` file, waits gracefully if it catches up with the
download, limits seeking to what's downloaded (unless hybrid seeking is enabled), and continues
seamlessly when the download completes and the file is renamed. Formats that can't be played
progressively are clearly marked with the reason.

## 2. Prerequisites
Phase 6 download engine (Room jobs, in-memory progress flow, `.part` files, finalize/rename),
Phase 5 player (`PlayerFactory`, error mapper, position saving).

## 3. Scope
**In:** eligibility detection, custom Media3 `DataSource` over a growing file, readiness
threshold + safe-start estimate, seek policy, UI states, finalize-during-playback, playback
position integration, optional hybrid network seek gated by `advanced_playback`.
**Out:** download priorities (12), moov-relocation tricks (future), HLS/DASH downloads (not supported).

---

## 4. Eligibility (`ProgressiveEligibility`)

Evaluate after the first bytes are on disk (re-evaluate as more arrive):

| Container | Rule | Result |
|---|---|---|
| MP4 / MOV / M4V | Walk top-level ISO-BMFF boxes from offset 0 (`size(4) type(4)`, handle 64-bit `largesize` and `size==0`). Eligible if `moov` appears **before** `mdat`. Ready only once the whole `moov` box is on disk. | `ELIGIBLE` / `NOT_ELIGIBLE(MOOV_AT_END)` |
| MPEG-TS (`.ts`, `video/mp2t`) | Stream format | `ELIGIBLE` (seek limited to downloaded range) |
| MKV / WebM | Clusters stream fine, but the Cues index is often at the end and the extractor may try to read it. **Verify empirically** with the fixtures: if playback starts and seeks within the downloaded range work with far reads failing fast (§5), mark `ELIGIBLE_NO_SEEK`; otherwise `NOT_ELIGIBLE(INDEX_AT_END)`. Record the finding in `docs/device-compatibility.md`. | |
| AVI | Index (`idx1`) at end | `NOT_ELIGIBLE(INDEX_AT_END)` |
| Audio (mp3/aac/m4a) | mp3/aac stream fine; m4a follows MP4 rule | as per rule |
| Unknown | — | `NOT_ELIGIBLE(UNKNOWN_FORMAT)` |
| Any, download not range-resumable and job restarts | — | progressive allowed, but warn that a restart would interrupt playback |

User-facing reasons (strings): "This file keeps its index at the end, so it can be played once
the download finishes." / "This format can't be played until it's fully downloaded."

---

## 5. `GrowingFileDataSource` (Media3 `DataSource`)

```kotlin
class GrowingFileDataSource(
  private val jobId: String,
  private val resolver: DownloadFileResolver,           // current uri (part or final) + open fd
  private val progress: StateFlow<Map<String, Progress>>,// written bytes per job (in-memory, 1 s or per chunk)
  private val jobState: Flow<DownloadState>,
  private val waitTimeoutMs: Long = 30_000,
  private val farReadWindowBytes: Long = 16L * 1024 * 1024
) : BaseDataSource(/* isNetwork = */ false)
```

Behaviour:
- `open(dataSpec)`: resolve the current URI via `resolver` **every open** (the part may have been
  renamed); reuse an already-open `ParcelFileDescriptor` when it's the same document; position the
  channel at `dataSpec.position`. Return `totalBytes - position` if total is known, otherwise
  `C.LENGTH_UNSET`.
- `read(buffer, offset, length)`:
  - `available = writtenBytes(jobId)` (or file length once COMPLETED).
  - If `position < available` → read `min(length, available - position)` bytes.
  - If `position ≥ available` and job is active:
    - if `position > available + farReadWindowBytes` → throw `NotYetDownloadedException(position, available)`
      immediately (fail fast — never block on far reads such as index-at-end lookups).
    - else suspend/wait (blocking with `runBlocking` on the loader thread is acceptable here; it's
      ExoPlayer's loading thread) on the progress flow until data arrives, the job leaves an
      active state, or `waitTimeoutMs` passes → then throw `IOException` so ExoPlayer rebuffers/retries.
  - If the job is PAUSED/FAILED and `position ≥ available` → throw `DownloadStalledException(state)`.
  - If COMPLETED and `position ≥ total` → `C.RESULT_END_OF_INPUT`.
- Report `bytesTransferred` for bandwidth meter consistency; `close()` releases resources.
- Use a `DataSource.Factory` per playback session; never share fds across sessions.

**Rename during playback:** the open fd stays valid after rename (same inode). New `open()` calls
resolve to `finalUri`. Add a test where finalize happens between two `open()` calls.

---

## 6. Readiness & safe start (`ProgressiveReadiness`)

Inputs: `written`, `total`, `durationMs` (metadata or MP4 `mvhd`), download `speedBps` (EWMA),
container eligibility.
- Estimated bitrate `b = total * 8 / durationSec` (bits/s; fallback 8 Mbit/s when unknown).
- Minimum start buffer `minStart = max(8 MB, b/8 * 20 s)` and, for MP4, `moov` fully present.
- Download rate `s` (bytes/s), playback consumption `c = b/8`.
- If `s ≥ 1.15·c` → **Ready** once `written ≥ minStart`.
- Else safe head start: `H = (D·c − written) / s − D` seconds (D = duration). If `H ≤ 0` → Ready;
  otherwise **Ready with warning**: "Download is slower than playback. It may pause to buffer.
  For uninterrupted viewing, wait about 6 min." (show `H`, updated live).
- Not eligible → **Unavailable(reason)**.

Expose `Flow<ProgressiveStatus>` per job: `Unavailable(reason) | Preparing(percentToReady) | Ready | ReadyWithWarning(waitMs)`.

---

## 7. Seek policy

- Allowed seek window = `[0, downloadedTimeMs − 5 s]` where `downloadedTimeMs ≈ written/total × duration`
  (for MP4 compute precisely from the sample table when practical — optional).
- Seeking past the window: clamp to the window edge and show "Not downloaded yet — 43 %" for 3 s.
- `ELIGIBLE_NO_SEEK` formats: seek disabled until completion (controls show a lock hint).
- **Hybrid seek (optional, entitlement `advanced_playback`):** for a seek beyond the window, use a
  `HybridDataSource` that serves `[0, written)` from disk and the rest via `OkHttpDataSource`
  `Range` requests to the source URL (the TV, not the backend). Clearly label "Streaming this
  part from the source". Off by default; on only when entitlement is true and the source supports
  ranges. Never used for non-range sources.

---

## 8. Player integration & UI (TV)

- Downloads screen and video detail show **Watch now** when status is `Ready`/`ReadyWithWarning`,
  a progress hint "Watch now available at 12 %" while `Preparing`, and the reason when `Unavailable`.
- `TvPlayerScreen` gets a `PlaybackSource` sealed type: `Remote(url)` (Phase 5), `Local(uri)`
  (completed download), `Growing(jobId)` (this phase). Media item MIME from metadata.
- Overlay chip: "Playing while downloading · 43 % downloaded · 4.1 MB/s".
- Seek bar: custom TV seek bar showing **downloaded range** (secondary track) separate from
  ExoPlayer's buffer.
- When the player rebuffers because it caught up: spinner + "Waiting for download (needs ~20 s)".
- If the download pauses/fails while watching: "Download paused — playback will stop at 43 %"
  with **Resume download** action; at the edge, pause the player with that message instead of an error.
- On completion during playback: chip changes to "Downloaded ✓" and seeking unlocks without
  restarting playback (re-prepare **not** required — just update the allowed window; if the
  format needed `ELIGIBLE_NO_SEEK`, offer "Enable seeking" which re-prepares at the current position).
- Position saving works exactly as Phase 5 (source-independent).
- Low-RAM TVs: keep `DefaultLoadControl` buffers modest for local sources (max 30 s).

---

## 9. Tests

### JVM unit
1. MP4 box walker: faststart, moov-at-end, 64-bit sizes, truncated header, `size==0` last box, garbage.
2. `GrowingFileDataSource` with a fake progress flow and temp file:
   - reads within available; waits then continues when progress advances; timeout → IOException;
   - far read → `NotYetDownloadedException` immediately; paused job → `DownloadStalledException`;
   - EOF only after COMPLETED; rename between opens resolves new URI; fd reuse.
3. `ProgressiveReadiness`: s ≥ c, s < c with head start formula, unknown duration, not eligible.
4. Seek policy clamp math.

### Instrumented (TV emulator + fixtures)
- `/slow.mp4` (faststart, throttled below bitrate) → Watch now with warning; plays; rebuffers; completes; no crash on rename.
- Fast faststart MP4 → Watch now within seconds; seek within window; seek beyond clamps.
- `/mp4/moov-at-end.mp4` → "can be played once the download finishes".
- TS fixture → plays progressively.
- MKV fixture → result documented (eligible-no-seek or not eligible).

---

## 10. File structure (new/changed)
```
android/tv/download/…/progressive/{ProgressiveEligibility.kt,Mp4BoxWalker.kt,ProgressiveReadiness.kt,ProgressiveStatus.kt}
android/core/player/…/{GrowingFileDataSource.kt,HybridDataSource.kt,DownloadFileResolver.kt,PlaybackSource.kt,SeekPolicy.kt}
android/app-tv/…/player/{TvPlayerScreen.kt (updated),DownloadedRangeSeekBar.kt,ProgressiveOverlay.kt}
android/app-tv/…/downloads/{DownloadRow.kt (Watch now)}
docs/device-compatibility.md (progressive results per container/device)
```

## 11. Rules
- Never read beyond written bytes; never block the main thread; never hold a lock the downloader needs.
- Downloader stays the only writer; the player only reads.
- Hybrid seek streams from the source directly to the TV (no backend involvement) and only for permitted, range-capable sources.

## 12. Acceptance criteria
- [ ] Eligible files show Watch now at the computed threshold; ineligible ones show a clear reason.
- [ ] Playback from a growing file works; catching up shows a waiting state, not an error.
- [ ] Slow download vs playback speed is detected and the safe-start estimate is shown.
- [ ] Seeking is limited to the downloaded range (or hybrid when entitled), with clear feedback.
- [ ] Download completion and rename during playback don't interrupt playback.
- [ ] Pausing/failing the download while watching is handled gracefully.
- [ ] Playback position saved/resumed as before.
- [ ] Unit + instrumented tests pass; device findings documented.

## 13. Manual test script
1. Start downloading `/slow.mp4` → Downloads row shows "Watch now available at 9 %" → becomes Watch now (with warning) → play.
2. Let playback catch up → waiting spinner → resumes automatically.
3. Press Right repeatedly past the downloaded edge → clamped + message.
4. Pause the download while watching → message at the edge → Resume download → continues.
5. Let it complete while watching → chip shows Downloaded ✓, full seeking works.
6. Try moov-at-end MP4 → Watch now disabled with reason.

## 14. Pitfalls
- ExoPlayer may open the source several times (sniffing, seeking) — each `open` must be cheap and correct.
- `MP4` `moov` can be large (tens of MB for long videos) — readiness must wait for the whole box.
- Emulators have fast disks; test on a real TV with a USB 2.0 stick where reads compete with writes.
- Don't base readiness on Room `verifiedBytes` (checkpointed); use the live written count.
