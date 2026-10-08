# VideoBridge — Phase Pack for Claude Code (VS Code)

This pack turns the *VideoBridge Complete Claude Code Development Plan* into 18 self-contained,
deeply specified phase files you hand to Claude Code one at a time.

## What is in the pack

| File | Purpose |
|---|---|
| `CLAUDE.md` | Master instructions. Copy to the **repo root**. Claude Code reads it automatically every session. |
| `00-architecture-and-conventions.md` | Shared contracts: repo layout, tech stack, API/error format, DB conventions, WebSocket envelope, event catalogue, download states, logging rules, definition of done. Every phase depends on it. |
| `PHASE-REPORT-TEMPLATE.md` | The report Claude must write at the end of each phase. |
| `phase-01-foundation.md` … `phase-18-release-preparation.md` | One file per phase: goal, scope, data model, API, tasks, file list, tests, acceptance criteria, manual test script, pitfalls, and **ready-to-paste prompts**. |

## One-time setup

1. Create an empty folder `videobridge/`, open it in VS Code, run `git init`.
2. Copy `CLAUDE.md` to the repo root.
3. Copy everything else into `docs/phases/` (so Claude can read the files by path).
4. Commit: `git add . && git commit -m "docs: add phase pack"`.
5. Install locally: Docker Desktop, Node.js LTS, JDK 17+ (Android Studio's bundled JBR is
   fine), Android Studio with SDK + an emulator for **Phone** and one for **Android TV**
   (API 34 TV image), `adb`. A real Android TV and a USB stick make Phases 6–7 far more
   reliable to test.
6. Open the Claude Code panel in VS Code.

## How to run each phase

Each phase file has a **"Prompts to paste"** section near the top. The rhythm is always:

1. `/clear` (fresh context per phase), and create a branch: `git checkout -b phase-04`.
2. Paste **Prompt A (plan)**. Claude reads the files, inspects the repo and proposes a
   numbered plan. Use **plan mode** (Shift+Tab until "plan mode" shows) for this step if you
   like. Read the plan; push back if anything looks wrong.
3. Paste **Prompt B (implement)**. Claude implements step by step, building, testing and
   committing as it goes.
4. Paste **Prompt C (verify & report)**. Claude runs every verification command, ticks the
   acceptance criteria and writes `docs/phase-reports/PHASE-XX-report.md`.
5. **You** run the manual test script from the phase file on the emulator/TV.
6. Fix anything with **Prompt D (fix)**, then merge the branch and update "Current status" in
   `CLAUDE.md`.

Tips:
- If the context gets long mid-phase, use `/compact`, then say *"Continue Phase XX from step N
  of your plan; re-read the phase file first."*
- Never paste two phases at once. The plan explicitly forbids it and quality drops sharply.
- Keep the emulator/TV attached (`adb devices`) during Android phases so Claude can install
  and run instrumentation tests.

## Recommended order

The fastest path to a usable product is Phases 1–10 (the MVP), then 11–18.

```
MVP milestone (after Phase 6/10):
Phone → Login → Paste/Share link → Save
TV    → Pair/Login → See saved link → Play → Choose USB/HDD → Download
```

| # | Phase | File | Depends on | MVP |
|---|---|---|---|---|
| 1 | Project foundation | `phase-01-foundation.md` | — | ✅ |
| 2 | Authentication (phone + OTP) | `phase-02-authentication.md` | 1 | ✅ |
| 3 | Device management & TV pairing | `phase-03-devices-and-tv-pairing.md` | 2 | ✅ |
| 4 | Saved video links (+ basic sync) | `phase-04-saved-video-links.md` | 3 | ✅ |
| 5 | Video metadata, compatibility & playback | `phase-05-metadata-compatibility-playback.md` | 4 | ✅ |
| 6 | Android TV download engine | `phase-06-tv-download-engine.md` | 5 | ✅ |
| 7 | Play while downloading | `phase-07-play-while-downloading.md` | 6 | ✅ |
| 8 | Subscription & billing | `phase-08-subscription-and-billing.md` | 3, 6 | ✅ |
| 9 | Phone UX polish | `phase-09-phone-ux.md` | 4–8 | ✅ |
| 10 | Android TV UX (ten-foot UI) | `phase-10-android-tv-ux.md` | 4–8 | ✅ |
| 11 | Real-time synchronization (robust) | `phase-11-realtime-sync.md` | 4, 6 |  |
| 12 | Smart download queue | `phase-12-smart-download-queue.md` | 6, 8, 11 |  |
| 13 | Admin dashboard | `phase-13-admin-dashboard.md` | 8 |  |
| 14 | Notifications | `phase-14-notifications.md` | 11 |  |
| 15 | Security hardening | `phase-15-security-hardening.md` | all |  |
| 16 | Complete testing | `phase-16-complete-testing.md` | all |  |
| 17 | Production deployment | `phase-17-production-deployment.md` | all |  |
| 18 | Release preparation | `phase-18-release-preparation.md` | all |  |

> The original plan lists "Sync → Queue → Progressive playback → Subscription → Admin →
> Security → Release" as the post-MVP order. Progressive playback (7) and subscription (8)
> are kept in numeric order here because Phase 8 replaces the entitlement stub the download
> engine already calls; doing it before the UX phases means the UX phases can show real plan
> limits. If you prefer, you can run 9 and 10 before 7 and 8 — the phase files are written so
> that works too.

## Key decisions already made in this pack (change them early if you disagree)

- **Backend:** NestJS + Prisma + PostgreSQL + Redis; WebSocket via `ws` (not socket.io).
- **Tokens:** 15-min JWT access + rotating opaque refresh tokens with reuse detection.
- **OTP delivery:** pluggable `SmsProvider` (MSG91 / Twilio / dev provider). Development uses
  fixed test numbers, never logs real OTPs.
- **TV login:** pairing via QR/short code approved on the phone is primary; OTP on the TV is a
  fallback.
- **Client-generated UUIDv7 IDs** for videos and download jobs (offline-first, idempotent).
- **Sync:** per-user monotonically increasing `seq` change log from Phase 4, hardened in Phase 11.
- **TV downloads:** WorkManager long-running workers + Room as source of truth; SAF tree URIs
  with an app-specific-directory fallback for TVs without a system folder picker.
- **One Play listing** (`com.videobridge.app`) with a phone AAB and an Android TV form-factor
  AAB. (Recorded as an ADR in Phase 1; switch to two package names there if you prefer.)
- **Admin:** separate React app + separate admin identities with TOTP 2FA.
- **Family plan:** higher limits in this plan; per-member family profiles are listed as future
  work (the `family_profiles` entitlement exists so it can be enabled later without app changes).

## Legal/product guardrails Claude will follow

VideoBridge plays and downloads **direct media URLs and standard unencrypted HLS/DASH streams
that the user is allowed to access**. It labels web pages, DRM-protected streams and
platforms whose terms prohibit downloading as unsupported. It does not implement DRM removal,
authentication bypass or extraction around a site's protections.
