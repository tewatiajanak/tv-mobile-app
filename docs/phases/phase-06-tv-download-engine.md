# Phase 6 — Android TV Download Engine

> **Implement this phase only. Do not start Phase 7. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-06-tv-download-engine.md
and the latest phase report. We are implementing Phase 6 (Android TV Download Engine) only.
Inspect core:player, core:data, the TV app, the metadata module and the entitlement stub first.
Check which Android TV emulator/API levels and real devices are available (adb devices).
Give me a numbered plan: tv:download module structure, Room schema, storage layer (SAF +
app-specific fallback + free-space + availability), downloader (HTTP range resume, .part files,
checkpoints), WorkManager scheduling, error/pause taxonomy, recovery, backend mirror tables and
endpoints, TV UI, and tests (unit with MockWebServer + emulator virtual disk). Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 6 step by step: storage layer → downloader core (pure Kotlin, heavily
unit-tested) → Room + repository → scheduler + worker + foreground notification → recovery and
broadcast handling → backend mirror + entitlement checks → TV UI → reporting to backend → tests.
Build, test and commit after each step. Never hardcode storage paths.
```

**Prompt C — verify & report**
```
Run all tests, then on the TV emulator: create a virtual removable disk (adb shell sm commands in
the phase file), add it as a storage location, download the fixture files, and demonstrate pause,
resume, network loss, unmount/remount, app kill, reboot and disk-full behaviour. Record results in
docs/device-compatibility.md. Tick acceptance criteria with evidence, write
docs/phase-reports/PHASE-06-report.md, update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure / logcat>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

From the TV, the user chooses **Download** on a video, picks a destination (internal storage,
a USB stick or an external HDD), and the TV downloads **directly from the source server** into a
`.part` file, renaming it only when complete. Downloads survive pause/resume, network loss,
USB removal and reinsertion, app kill, and TV reboot. Progress, speed and ETA are visible on the
TV and (as data) on the backend for the phone. Limits come from entitlements.

## 2. Prerequisites
Phase 5 (metadata with `downloadable`, `contentLengthBytes`, `acceptsRanges`, ETag/Last-Modified;
TV player; fixture server incl. `/slow.mp4`, `/flaky.mp4`, `/no-range.mp4`, `/expiring.mp4`).

## 3. Scope
**In:** `:tv:download` module, storage locations (SAF + fallback), downloader with range resume
and checkpoints, queue with `max_active_downloads`/`max_queued_downloads`, WorkManager
long-running workers with foreground notification, all states/pause reasons/failure codes,
recovery, USB mount/unmount handling, disk-space checks, FAT32 4 GB detection, backend mirror
(`storage_locations`, `download_jobs`, `download_events`) + events, TV UI (download button,
storage picker, downloads screen, storage settings), playing completed downloads.
**Out:** play-while-downloading (7), priorities/scheduling/bandwidth/Wi-Fi-only (12), phone
remote "download on TV" command (11), HLS/DASH downloads (not supported — streams are play-only).

---

## 4. Module & components (`android/tv/download`)

```
com.videobridge.tv.download
├── model/        DownloadJob, DownloadState, PauseReason, FailureCode, StorageLocation, StorageKind, Progress
├── storage/      StorageLocationsRepository, SafTreeStorage, AppSpecificStorage, StorageWriter (interface),
│                 FreeSpaceProbe, VolumeMonitor (mount/unmount broadcasts + StorageVolume callbacks),
│                 FileNamePolicy, FilesystemLimits
├── engine/       HttpDownloader (pure Kotlin + OkHttp), RangeResumePolicy, Checkpointer,
│                 SpeedMeter (EWMA), RetryPolicy, ErrorClassifier, Throttle (no-op; Phase 12)
├── queue/        DownloadScheduler, DownloadWorker, ForegroundNotifier, DownloadRecovery
├── data/         DownloadDao, StorageLocationDao, DownloadRepository, DownloadReporter (backend sync)
└── di/           DownloadModule
```

Design rule: `HttpDownloader` depends only on interfaces (`StorageWriter`, `Clock`, `OkHttpClient`)
so every edge case is unit-testable on the JVM without Android.

---

## 5. Storage layer

### 5.1 Adding a location (preferred: SAF)
1. Enumerate volumes with `StorageManager.storageVolumes` → show the user friendly names
   (`StorageVolume.getDescription()`), "Internal storage" for primary, removable volumes as
   "USB / external drive: <description>".
2. Launch `ACTION_OPEN_DOCUMENT_TREE` (API 29+: `volume.createOpenDocumentTreeIntent()` so the
   picker starts on that volume). Suggest creating/choosing a `VideoBridge` folder.
3. `contentResolver.takePersistableUriPermission(uri, READ|WRITE)`.
4. **Write probe:** create `.videobridge-probe` in the tree, write 4 KB, `fsync`, read back,
   keep it (used for free-space checks). Failure → "This drive is read-only on this TV" (common
   for NTFS on Android TV) and the location is not saved.
5. Map the tree to a volume: for `ExternalStorageProvider`, the tree document id is
   `<volumeUuid>:<path>` or `primary:<path>` — store `volumeUuid` (nullable; provider-specific, so
   always fall back to probe-based availability).
6. Save `StorageLocation(id=UUIDv7, kind, label, treeUri, volumeUuid, isDefault, addedAt)`.
   Enforce `max_storage_destinations`.

### 5.2 Fallback: app-specific external directories
Many Android TV builds ship **without a usable DocumentsUI** (the picker activity is missing or
can't see USB). Detect with `intent.resolveActivity(packageManager) == null` or an
`ActivityNotFoundException`. Fallback: `context.getExternalFilesDirs(null)` — entries beyond
index 0 are removable volumes mounted for this app; no permission needed; kind `APP_SPECIFIC`.
Show a clear warning: "Files saved here are removed if VideoBridge is uninstalled and are only
visible to VideoBridge." Never construct these paths yourself — only use what the API returns.

### 5.3 Free space & availability
- SAF: open the probe with `openFileDescriptor(probeUri, "r")` → `Os.fstatvfs(fd)` →
  `f_bavail * f_frsize` (free) and `f_blocks * f_frsize` (total).
- App-specific: `StatFs(dir.path)`.
- Availability = probe openable **and** (if `volumeUuid` known) the volume's state is `mounted`.
- `VolumeMonitor`: dynamic receiver for `ACTION_MEDIA_MOUNTED`, `ACTION_MEDIA_UNMOUNTED`,
  `ACTION_MEDIA_EJECT`, `ACTION_MEDIA_REMOVED`, `ACTION_MEDIA_BAD_REMOVAL` (data scheme `file`) and,
  on API 30+, `StorageManager.registerStorageVolumeCallback`. Emits `Flow<Set<locationId>>` of available locations.

### 5.4 Filesystem limits
- FAT32 can't hold files ≥ 4 GiB. Filesystem type isn't exposed through SAF, so:
  - Pre-flight: if `totalBytes ≥ 4 GiB` and location kind is removable → confirmation dialog
    "If this drive is FAT32, files over 4 GB can't be saved. exFAT/NTFS drives are fine. Continue?"
  - At runtime, `EFBIG` (or a write failure exactly at 4 GiB − 1) → `FAILED(FILE_TOO_LARGE_FOR_FILESYSTEM)`
    and mark the location `fat32Suspected = true` (warn earlier next time).
- Name policy (`FileNamePolicy`): from metadata filename or title; remove `/ \ : * ? " < > |` and
  control chars, collapse whitespace, trim dots/spaces, max 120 chars + extension; extension from
  filename or content type (`video/mp4` → `mp4`, `video/x-matroska` → `mkv`, …). Collisions →
  `Name (2).mp4`.

### 5.5 Writer abstraction
```kotlin
interface StorageWriter {
  suspend fun createPart(location: StorageLocation, displayName: String): PartHandle   // "<name>.<ext>.part", mime application/octet-stream
  suspend fun open(part: PartHandle): PartChannel                                        // "rw" FileChannel
  suspend fun sizeOnDisk(part: PartHandle): Long
  suspend fun truncate(part: PartHandle, size: Long)
  suspend fun finalize(part: PartHandle, finalName: String): FinalHandle                  // rename; resolve collisions
  suspend fun delete(handle: Handle)
  suspend fun exists(handle: Handle): Boolean
}
```
SAF implementation: `DocumentsContract.createDocument` with MIME `application/octet-stream` (a
video MIME makes some providers append/alter extensions), `openFileDescriptor(uri, "rw")` →
`FileOutputStream(fd).channel`, `DocumentsContract.renameDocument` for finalize (if the provider
doesn't support rename → keep working with a copy-free fallback: create the document with the
final name from the start and track completion only in Room; record this per device in
`docs/device-compatibility.md`). App-specific implementation uses `java.io.File`.

---

## 6. Downloader algorithm (`HttpDownloader.run(job)`)

```
1  PRECHECK   metadata.downloadable? else FAILED(UNSUPPORTED_MEDIA)
              location available? else PAUSED(STORAGE_REMOVED)
2  PROBE      HEAD (fallback GET Range 0-0): totalBytes, acceptsRanges, ETag, Last-Modified, final URL
              403/401/410 → FAILED(HTTP_4XX, "Link expired or access denied"); 404 → FAILED(HTTP_4XX, NOT_FOUND)
              if stored validator exists and differs → SOURCE_CHANGED → discard part, restart from 0 (event logged)
3  SPACE      need = (totalBytes − verifiedBytes) + reserve(max(200 MB, 2 % of total))
              free < need → FAILED(INSUFFICIENT_STORAGE) with details {needed, free}
              unknown totalBytes → require free ≥ 1 GB and re-check every 256 MB
4  PART       create part if missing; on resume: onDisk = sizeOnDisk(part)
              start = min(onDisk, job.verifiedBytes); truncate(part, start)   // drop unverified tail
5  REQUEST    start > 0 && acceptsRanges → "Range: bytes=start-" + "If-Range: <ETag or Last-Modified>"
              206 + Content-Range start matches → append at start
              200 → server ignored range or file changed → truncate(0), start = 0
              416 → if start == totalBytes → go to 8, else truncate(0) and retry once
6  STREAM     64–256 KB buffer; write via channel at position; after each chunk:
                - check cancellation / pause (worker isStopped or job state changed)
                - speedMeter.add(bytes), throttle.acquire(bytes)        // throttle is no-op until Phase 12
                - every 1 s: emit Progress(bytes, total, speedBps, etaMs) to Room (in-memory flow for UI)
                - every 16 MB or 10 s: channel.force(false) (fsync) → job.verifiedBytes = position (Room)
7  END        stream ended: if total known and bytes != total → treat as network error (resume)
8  FINALIZE   force(true); close; verify size == total (when known); finalize(part, finalName) → COMPLETED
              save finalUri, completedAt; emit DOWNLOAD_COMPLETED
```

Speed = EWMA of 1-second samples (α = 0.3); ETA = remaining / speed (hidden when speed < 1 KB/s
or unknown total).

### 6.1 Error classification → state
| Condition | Result |
|---|---|
| `UnknownHostException`, `SocketTimeoutException`, `ConnectException`, connection reset, stream ended early | `RETRYING` with backoff 2 s, 4 s, 8 s … 5 min (jitter); after 10 consecutive failures → `PAUSED(NETWORK_LOST)` waiting for connectivity |
| No network (`ConnectivityManager` reports none) | `PAUSED(NETWORK_LOST)`; WorkManager constraint resumes |
| HTTP 5xx / 429 | `RETRYING` (respect `Retry-After`), max 8 → `FAILED(HTTP_5XX)` |
| HTTP 401/403/404/410 | `FAILED(HTTP_4XX)` with message; user can re-check the link |
| `ENOSPC` | `FAILED(INSUFFICIENT_STORAGE)` |
| `EFBIG` | `FAILED(FILE_TOO_LARGE_FOR_FILESYSTEM)` |
| `EIO`/`ENOENT`/`FileNotFoundException`/`IllegalStateException` from provider while volume unmounted | `PAUSED(STORAGE_REMOVED)` → auto-resume on remount |
| `SecurityException` on the tree URI | `FAILED(STORAGE_PERMISSION_LOST)` → UI asks to re-select the folder |
| Validator mismatch (If-Range → 200 on a resumed job) | restart from 0, event `SOURCE_CHANGED` |
| Worker stopped by system (constraints, FGS timeout on Android 15+) | `PAUSED(APP_RESTART)` → rescheduled |

Map errno via `ErrnoException` causes (`OsConstants.ENOSPC`, `EFBIG`, `EIO`, `ENOENT`).

---

## 7. Scheduling & workers

- **Room is the source of truth.** `DownloadRepository.enqueue(videoId, locationId)` validates
  entitlements (cached `max_active_downloads`, `max_queued_downloads`), registers the job with
  the backend (`POST /downloads`, see §9; if offline, allowed when the cached entitlements are
  < 7 days old and registered later), inserts `QUEUED`, calls `DownloadScheduler.reconcile()`.
- `DownloadScheduler.reconcile()` (mutex-protected; called on every state change, network
  change, storage change, entitlement change, app start): `slots = maxActive − running`; pick
  the oldest QUEUED jobs whose location is available → enqueue `DownloadWorker` as **unique work**
  `download-<jobId>` (`ExistingWorkPolicy.KEEP`), constraint `NetworkType.CONNECTED`.
- `DownloadWorker` (`CoroutineWorker`): `setForeground(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`
  first; runs `HttpDownloader`; maps outcome to state; returns `Result.success()` for terminal
  states, `Result.retry()` only for transient failures not handled internally.
  - Catch `ForegroundServiceStartNotAllowedException` (Android 12+, background start) → leave
    `QUEUED` with reason `APP_RESTART`; jobs resume when the app is next opened. Document per device.
  - Android 15+ `dataSync` time limits: handle `onStopped`/timeout → `PAUSED(APP_RESTART)`, resume later.
- **Pause/resume/cancel/retry:** pause → state PAUSED(USER) + `cancelUniqueWork`; resume →
  QUEUED + reconcile; cancel → CANCELLED, cancel work, delete `.part`; retry (from FAILED) →
  QUEUED (re-probe). "Pause all"/"Resume all" are Phase 12.
- **Foreground notification:** one ongoing notification "Downloading 2 videos · 45 % · 3 min left"
  with a Pause-all action; channel `downloads` (low importance). TV launchers rarely show it, but
  it's required for the foreground service.
- **Recovery** (`DownloadRecovery`, on `Application.onCreate` and `BOOT_COMPLETED`):
  jobs in `STARTING/DOWNLOADING/RETRYING` with no running work → `QUEUED`; verify `.part`
  sizes vs `verifiedBytes`; verify COMPLETED files still exist (if the volume is mounted) else
  mark `fileMissing`; then reconcile.
- Manifest:
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
  <uses-permission android:name="android.permission.WAKE_LOCK"/>
  <service android:name="androidx.work.impl.foreground.SystemForegroundService"
           android:foregroundServiceType="dataSync" tools:node="merge"/>
  ```
  Keep the Wi-Fi awake during downloads (`WifiManager.WifiLock` `WIFI_MODE_FULL_HIGH_PERF`, released on stop).

---

## 8. Room schema (TV)

```kotlin
@Entity(tableName = "download_jobs", indices = [Index("state"), Index("videoId")])
data class DownloadJobEntity(
  @PrimaryKey val id: String,              // UUIDv7, also used by backend
  val videoId: String, val locationId: String,
  val sourceUrl: String, val displayName: String, val finalName: String?,
  val state: DownloadState, val pauseReason: PauseReason?, val failureCode: FailureCode?, val failureMessage: String?,
  val totalBytes: Long?, val downloadedBytes: Long, val verifiedBytes: Long,
  val etag: String?, val lastModified: String?, val acceptsRanges: Boolean?,
  val partUri: String?, val finalUri: String?,
  val attempt: Int, val nextRetryAt: Instant?,
  val createdAt: Instant, val startedAt: Instant?, val completedAt: Instant?, val updatedAt: Instant,
  val reportedVersion: Int                // last state version acknowledged by backend
)
@Entity(tableName = "storage_locations")
data class StorageLocationEntity(
  @PrimaryKey val id: String, val kind: StorageKind,            // INTERNAL, REMOVABLE, APP_SPECIFIC
  val label: String, val treeUri: String?, val dirPath: String?, // dirPath only for APP_SPECIFIC (from getExternalFilesDirs)
  val volumeUuid: String?, val probeUri: String?, val isDefault: Boolean,
  val fat32Suspected: Boolean, val addedAt: Instant
)
```
Progress updates go to an in-memory `StateFlow<Map<jobId, Progress>>` (1 s) and Room only on
checkpoint, so Room isn't hammered.

---

## 9. Backend mirror (migration `…_downloads`)

```prisma
enum DownloadState { QUEUED STARTING DOWNLOADING PAUSED COMPLETED FAILED CANCELLED RETRYING }
enum StorageKind { INTERNAL REMOVABLE APP_SPECIFIC }

model StorageLocation {
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  deviceId String @map("device_id") @db.Uuid
  kind StorageKind
  label String @db.VarChar(80)
  totalBytes BigInt? @map("total_bytes")
  freeBytes BigInt? @map("free_bytes")
  isAvailable Boolean @default(true) @map("is_available")
  lastSeenAt DateTime @map("last_seen_at") @db.Timestamptz
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  updatedAt DateTime @updatedAt @map("updated_at") @db.Timestamptz
  deletedAt DateTime? @map("deleted_at") @db.Timestamptz
  @@index([userId, deviceId])
  @@map("storage_locations")
}

model DownloadJob {
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  deviceId String @map("device_id") @db.Uuid
  videoId String @map("video_id") @db.Uuid
  storageLocationId String? @map("storage_location_id") @db.Uuid
  state DownloadState
  pauseReason String? @map("pause_reason") @db.VarChar(30)
  failureCode String? @map("failure_code") @db.VarChar(40)
  failureMessage String? @map("failure_message") @db.VarChar(300)
  totalBytes BigInt? @map("total_bytes")
  downloadedBytes BigInt @default(0) @map("downloaded_bytes")
  fileName String? @map("file_name") @db.VarChar(260)
  priority Int @default(0)                              // used in Phase 12
  version Int @default(1)
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  startedAt DateTime? @map("started_at") @db.Timestamptz
  completedAt DateTime? @map("completed_at") @db.Timestamptz
  updatedAt DateTime @updatedAt @map("updated_at") @db.Timestamptz
  deletedAt DateTime? @map("deleted_at") @db.Timestamptz
  @@index([userId, state])
  @@index([deviceId, state])
  @@map("download_jobs")
}

model DownloadEvent {
  id String @id @db.Uuid
  jobId String @map("job_id") @db.Uuid
  userId String @map("user_id") @db.Uuid
  fromState DownloadState? @map("from_state")
  toState DownloadState @map("to_state")
  reason String? @db.VarChar(40)
  bytes BigInt?
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  @@index([jobId, createdAt])
  @@map("download_events")
}
```
No URIs, paths or file contents are stored server-side — only labels and numbers.

### Endpoints
| Method | Path | Notes |
|---|---|---|
| PUT | `/api/v1/storage/locations/{id}` | TV upserts `{kind,label,totalBytes,freeBytes,isAvailable}`; enforces `max_storage_destinations` |
| GET | `/api/v1/storage/locations?deviceId=` | for the phone |
| DELETE | `/api/v1/storage/locations/{id}` | soft delete |
| POST | `/api/v1/downloads` | register `{id, videoId, storageLocationId, totalBytes?, fileName}` from a TV device; checks `max_active_downloads` (STARTING+DOWNLOADING+RETRYING) and `max_queued_downloads` (QUEUED+PAUSED) per user; `ENTITLEMENT_LIMIT` on breach; idempotent on `id` |
| PATCH | `/api/v1/downloads/{id}` | state transition `{state, pauseReason?, failureCode?, failureMessage?, downloadedBytes, totalBytes?, version}`; validates allowed transitions (§12 of conventions); records `download_events`; emits persisted `DOWNLOAD_STARTED/COMPLETED/FAILED/DOWNLOAD_STATE_CHANGED` |
| GET | `/api/v1/downloads?deviceId=&state=` | list for phone/TV |
| DELETE | `/api/v1/downloads/{id}` | soft delete (TV removed it) |
| WS in | `DOWNLOAD_PROGRESS {jobId, bytes, totalBytes, speedBps, etaMs}` | from the owning TV only; rate-limited 1/s per job; fanned out (ephemeral) to the user's other devices; `downloaded_bytes` persisted at most every 30 s |

`DownloadReporter` on the TV: sends PATCH on every state change (outbox with retry when
offline), progress over WS every 2 s while connected.

---

## 10. TV UI
- **Video detail → Download**: disabled with reason if not downloadable. If no location →
  "Choose where to save downloads" flow (§5). If several → picker dialog showing label, free
  space, availability (default preselected; "Remember as default"). Large-file FAT32 warning
  when applicable. Then toast "Added to downloads".
- **Downloads screen** (`TvDownloadsScreen`): sections *Downloading*, *Queued*, *Paused*,
  *Failed*, *Completed*; each row: title, progress bar, `1.2 GB of 3.4 GB · 4.5 MB/s · 8 min left`,
  state/reason text ("Waiting for USB drive", "Waiting for network", "Link expired"); actions via
  focusable buttons: Pause/Resume, Cancel (confirm), Retry, Play (completed), Delete file (confirm),
  Change location (failed/paused only).
- **Storage settings** (`TvStorageSettingsScreen`): list locations with kind icon, free/total,
  available/unavailable, default star; Add location; Remove (offer to keep files); "Re-grant
  access" for permission-lost; read-only/FAT32 warnings.
- **Playing completed downloads**: `MediaItem` from the content URI / file; check existence first
  → "Storage not connected — insert the USB drive" if missing.
- Video detail shows a "Downloaded on <label>" chip; deleting a video asks "Also delete the
  downloaded file?".

---

## 11. Tests

### Unit (JVM, MockWebServer + `FakeStorageWriter` with failure injection)
1. Full download, size verified, renamed from `.part`.
2. Resume with `Range` + `If-Range` → 206 appended correctly (byte-for-byte compare).
3. Resume but server returns 200 → restart from 0.
4. Validator changed → `SOURCE_CHANGED` restart.
5. 416 at exact end → completes; 416 mid-file → restart.
6. No `Accept-Ranges` + network drop → restarts from 0 on retry (and UI warns "can't resume").
7. Connection drop mid-stream (`/flaky.mp4` semantics) → RETRYING with backoff → completes.
8. Unknown total size (chunked) → completes on EOF.
9. `ENOSPC` at 70 % → FAILED(INSUFFICIENT_STORAGE); `EFBIG` → FILE_TOO_LARGE_FOR_FILESYSTEM.
10. `EIO` after storage removed → PAUSED(STORAGE_REMOVED); resume after "mount" → completes.
11. `SecurityException` → FAILED(STORAGE_PERMISSION_LOST).
12. Crash simulation: kill after write but before checkpoint → resume truncates to `verifiedBytes`; final file still byte-identical.
13. Cancel deletes `.part`; completed downloads untouched.
14. 403 → FAILED(HTTP_4XX) with "Link expired" message; 503 + Retry-After respected.
15. Scheduler respects `max_active` (e.g. 1) and starts the next when one completes; unavailable location skipped.
16. FileNamePolicy: illegal chars, emoji, very long names, collisions, extension inference.
17. SpeedMeter/ETA math with a fake clock.

### Backend
- Register/transition/list/delete e2e; invalid transitions → 409 `INVALID_STATE_TRANSITION`;
  limits for active/queued; progress fan-out reaches the phone socket only for the same user;
  progress from a non-owner device rejected.

### Emulator / real device (manual-assisted, scripted where possible)
```bash
adb shell sm set-virtual-disk true          # creates a virtual removable disk
adb shell sm list-disks                     # e.g. disk:7,8
adb shell sm partition disk:7,8 public      # format as portable storage
adb shell sm list-volumes                   # note the volume id, e.g. public:7,9
adb shell sm unmount public:7,9             # simulate USB removal
adb shell sm mount public:7,9               # reinsert
adb shell svc wifi disable / enable         # network loss (or emulator network toggle)
adb shell am force-stop <tv-package>        # app kill
adb reboot                                  # reboot recovery
adb shell fallocate -l <size> /sdcard/fill  # (or a large file on the virtual disk) to provoke ENOSPC
```

---

## 12. File structure (new/changed)
```
android/tv/download/** (see §4)
android/app-tv/…/downloads/{TvDownloadsScreen.kt,TvDownloadsViewModel.kt,DownloadRow.kt}
android/app-tv/…/storage/{TvStorageSettingsScreen.kt,StoragePickerDialog.kt,AddStorageFlow.kt}
android/app-tv/src/main/AndroidManifest.xml (permissions, FGS type, boot receiver)
backend/src/modules/storage/{storage.module.ts,storage.controller.ts,storage.service.ts}
backend/src/modules/downloads/{downloads.module.ts,downloads.controller.ts,downloads.service.ts,transitions.ts,progress.gateway-handler.ts}
backend/test/{downloads.e2e-spec.ts,storage.e2e-spec.ts}
docs/device-compatibility.md (filled with emulator + any real TV results)
docs/decisions/ADR-0006-download-engine.md
```

## 13. Security & product rules
- TV downloads straight from the source; backend never sees bytes.
- Only `downloadable` media (DIRECT_MEDIA). No HLS/DASH segment downloading, no DRM, no
  cookie/credential replay from browsers.
- Never log full URLs (query strings) or document URIs; log job id + host only.
- Persisted URI permissions are the only storage access; no `MANAGE_EXTERNAL_STORAGE`, no raw paths.
- Backend validates that the reporting device owns the job and the video belongs to the user.

## 14. Acceptance criteria
- [ ] Add internal storage and a removable (virtual/real USB) location via SAF; fallback path works when the picker is unavailable (simulate by forcing the fallback flag).
- [ ] Read-only drives rejected at add time with a clear message.
- [ ] Download writes `<name>.part` and renames to the final name only after verified completion.
- [ ] Pause/resume (range resume, no re-download from 0 when the server supports ranges), cancel (part deleted), retry.
- [ ] Progress, speed and ETA update every second on the TV; backend/phone receive progress.
- [ ] Network loss → waits and resumes automatically; flaky server → completes with retries.
- [ ] USB unmount → PAUSED(STORAGE_REMOVED); remount → resumes automatically; file intact (checksum equal to source).
- [ ] App kill and TV reboot → downloads resume.
- [ ] Insufficient space detected before starting and during download.
- [ ] Files ≥ 4 GiB on removable storage get the FAT32 warning; EFBIG handled.
- [ ] `max_active_downloads` and `max_queued_downloads` enforced locally and by the backend.
- [ ] Completed downloads play from storage; missing storage shows a clear message.
- [ ] All unit/e2e tests pass; device-compatibility doc updated; ADR-0006 written.

## 15. Pitfalls
- `DocumentFile.length()`/`listFiles()` are slow (IPC per call) — never call them in the write loop.
- `openFileDescriptor(uri, "wa")` is not reliably append on all providers — use `"rw"` + explicit channel position.
- Some providers append `(1)` to names on create; always read back the returned document's display name.
- Don't hold a `WakeLock` beyond the worker; WorkManager's FGS handles CPU, the Wi-Fi lock handles radio.
- `fsync` on USB sticks is slow — checkpoint every 16 MB / 10 s, not per chunk.
- Emulator virtual disks don't model FAT32 limits or slow USB 2.0 writes; test on a real TV with a FAT32 stick before calling this done.
