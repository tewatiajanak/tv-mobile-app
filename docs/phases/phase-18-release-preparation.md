# Phase 18 — Release Preparation

> **Implement this phase only. This is the final phase. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-18-release-preparation.md,
docs/testing/test-results-*.md and the latest phase report. We are implementing Phase 18 (Release Preparation) only.
Inspect both apps' build configs, manifests, resources, version handling and signing setup.
Give me a numbered plan: versioning, icons/splash/banner, release build config (R8, rules),
secure signing with Play App Signing, legal/support/About content, Play Console listings for phone
and TV (texts, graphics, screenshots, Data safety, content rating, app access for reviewers),
testing tracks and the closed-testing requirement, release checklist, final E2E verification, and
staged rollout + rollback. List everything I must do in Play Console myself. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement the in-repo parts step by step (versioning, assets, release config, signing via
environment, CI upload to the internal track, legal pages, store listing drafts, screenshot
automation, checklists) and commit. Write exact Play Console instructions for the parts I do.
Never commit keystores or passwords.
```

**Prompt C — verify & report**
```
Build signed release AABs for phone and TV from CI, upload them to the internal testing track,
install from Play on a phone and a TV, and run the final end-to-end verification script. Fill in
docs/release/release-checklist.md with evidence. Write docs/phase-reports/PHASE-18-report.md and
update CLAUDE.md (status: release candidate). Stop.
```

**Prompt D — fix**
```
<paste build/upload/review issue>. Root-cause first, explain briefly, smallest fix, rebuild, commit.
```

---

## 1. Goal

Release-quality phone and TV builds, securely signed, with final branding, legal and support
content, complete Google Play listings for both form factors, a verified end-to-end run on real
devices installed from Play, and a staged rollout plan.

## 2. Versioning
- `version.properties`: `VERSION_MAJOR/MINOR/PATCH`, `VERSION_BUILD` (CI run number).
- `versionName = "M.m.p"`; `versionCode` per ADR-0003 (phone `base*10+1`, TV `base*10+2`,
  `base = M*1_000_000 + m*10_000 + p*100 + build%100` or simply a monotonically increasing CI
  number) — document the formula and guard it with a unit test (must always increase, < 2,100,000,000).
- Backend reports minimum supported app versions via `/config` (Phase 13); release notes in `CHANGELOG.md`.

## 3. Branding assets
- **App icon:** adaptive icon (foreground/background layers) + **monochrome** layer for themed
  icons; legacy PNGs; Play Store icon 512×512 PNG (32-bit, no transparency issues).
- **Splash:** AndroidX `core-splashscreen` (icon + brand background; no custom splash activity);
  TV uses the same API with a dark background.
- **TV banner:** 320×180 dp (`xhdpi` 640×360 px recommended) with the app name visible; used by the launcher.
- **Feature graphic:** 1024×500. **TV banner for Play:** 1280×720.
- Keep sources (`.svg`/design files) in `design/` (not in APK).

## 4. Release build configuration
- R8 full mode + resource shrinking; keep rules for kotlinx.serialization (`@Serializable`),
  Retrofit interfaces, Room, Hilt (mostly automatic), Media3, Tink, Play Billing, Firebase; verify
  with a **release-build smoke test** (instrumented tests on `release` variant with the `staging` flavor).
- `debuggable false`, logging trees off, StrictMode off, cleartext off (prod), `allowBackup`/
  extraction rules final, `android:usesCleartextTraffic` absent in prod.
- Baseline profiles included (Phases 9/10). App size check (report AAB/APK sizes per ABI/density).
- Crash reporting and analytics (if any) respect consent; no ads SDKs.

## 5. Signing (never commit keys)
- Use **Play App Signing**: Google holds the app signing key; you keep an **upload key**.
- Generate the upload keystore locally (`keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload`), store it in a password manager, and add to CI as
  base64 secrets: `UPLOAD_KEYSTORE_B64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`.
- Gradle `signingConfigs.release` reads **only** from environment/Gradle properties; build fails
  clearly if missing; `.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties`.
- Record upload + app-signing certificate SHA-256 fingerprints in `docs/release/signing.md` and in
  `assetlinks.json` (Phase 17).
- CI workflow `android-release.yml`: build `prodRelease` AABs for phone and TV → upload to Play
  **internal** track via Gradle Play Publisher or fastlane `supply` using a Play service account
  (secret), phone AAB to the main track set, TV AAB to the **Android TV form-factor track**.

## 6. In-app legal, support and About
- Privacy policy and Terms (hosted on `web/`, linked in app and listing); plain-language summary
  screen in Settings → Privacy. Cover: data collected (phone number, device info, saved links and
  notes, playback positions, download metadata, purchase records, crash diagnostics), purposes,
  retention, sharing (SMS provider, Google Play, Firebase, hosting), user rights (export, deletion),
  contact, grievance officer (DPDP — legal review).
- Content policy statement in Terms: users may only save/download content they have the right to;
  VideoBridge doesn't support DRM-protected or platform-restricted content.
- Support: email + web support page, in-app "Contact support" with app version/device id prefilled;
  FAQ (USB formats, FAT32 4 GB limit, why some links are unsupported, how pairing works).
- About: version/build, licenses (`oss-licenses-plugin` or AboutLibraries), links.

## 7. Google Play listing (both form factors, one listing per ADR-0003)

### 7.1 Store listing drafts (`docs/release/store-listing/`)
- App name (≤ 30 chars), short description (≤ 80), full description (≤ 4,000) in **English and
  Hindi**. Describe saving links from the phone, TV pairing, playback, downloads to USB/HDD.
  **Do not** advertise downloading from YouTube or other platforms that forbid it (policy risk);
  say "direct video links you're allowed to use".
- Screenshots: phone (≥ 4, portrait 1080×1920 or larger), tablet optional, **TV ≥ 1 at 1920×1080**
  (provide 4: Home rows, Detail, Downloads with USB, Player). Automate with screenshot tests +
  a "demo data" seeder in a `demo` build flavor (no real user data, no copyrighted thumbnails —
  use generated artwork).
- Feature graphic 1024×500; TV banner 1280×720; promo video optional.

### 7.2 Policy forms (prepare answers in `docs/release/play-forms.md`)
- **Data safety:** map every data type above to collected/shared, purpose, optional/required,
  encrypted in transit (yes), deletion request mechanism (in-app + web URL).
- **Account deletion URL:** `https://<domain>/delete-account` (Phase 17 web).
- **Content rating** questionnaire (utility/productivity; user-generated links not publicly shared).
- **Target audience:** 18+ / not designed for children.
- **Ads:** none. **Financial features:** subscriptions via Play Billing only.
- **App access for reviewers:** a dedicated **Play review account**. Because Phase 2 forbids test
  OTP numbers in production, implement a narrowly scoped exception (ADR required):
  `PLAY_REVIEW_PHONE` + `PLAY_REVIEW_OTP_HASH` config → only that number accepts its fixed code;
  rate-limited, audited, account flagged `isReviewAccount` (no SMS sent, cannot pair more than the
  FREE limit, can be disabled from admin). Provide reviewers with instructions incl. TV pairing
  (they can pair the TV using the phone review account, or log in on TV with the same number/code).
- **Android TV quality:** leanback launcher intent, banner, no touch requirement, D-pad navigation
  everywhere, no phone-only features required on TV; TV builds go through Google's additional TV review — expect feedback cycles.
- Subscription products and base plans created in Play Console match `plan_prices` (Phase 8).

### 7.3 Testing tracks
- Internal testing (team) → **Closed testing**. Note: **new personal developer accounts must run a
  closed test with at least 12 testers opted in for 14 continuous days** before requesting production
  access — plan for it (organisation accounts are exempt; verify current Play rules when you start).
- Review the **pre-launch report** (crashes, accessibility, security warnings) for each build.

## 8. Final end-to-end verification (`docs/release/e2e-script.md`)
Installed **from Play (internal/closed track)** on a real phone and a real Android TV, against production backend:
1. Install phone app → onboarding → sign in with a real number (real SMS) → share a link from WhatsApp → library.
2. Install TV app → pairing QR → approve on phone → library appears.
3. Play a direct MP4 on TV → resume across devices.
4. Add USB drive → download (internal + USB) → unplug/replug → completes → play from USB; Watch-now on a progressive file.
5. Phone "Download on TV" while TV is off → turn TV on → starts.
6. Purchase PLUS with a license tester → limits change live → cancel in Play → access until period end.
7. Notifications: download completed push; quiet hours respected.
8. Delete account (separate test account) → data removed after grace; Play subscription warning shown.
9. Admin: find the test user, view audit trail of the session.
Record device models, versions, results, and screenshots.

## 9. Release checklist (`docs/release/release-checklist.md`)
- [ ] All phase reports complete; Phase 16 verdict Go; no open S1/S2.
- [ ] Security audit: no open High/Critical; secrets rotated from any value ever used in staging.
- [ ] Production backend deployed, monitored, backups verified (Phase 17).
- [ ] `versionCode`s increased; CHANGELOG updated; git tag `v1.0.0`.
- [ ] Release AABs built by CI, signed with the upload key, uploaded; Play App Signing enabled.
- [ ] R8 release smoke tests passed; sizes recorded.
- [ ] Icons, splash, TV banner, feature graphic, screenshots (phone + TV), listings EN/HI done.
- [ ] Privacy policy, terms, support, delete-account pages live and linked.
- [ ] Data safety, content rating, target audience, ads, app access, account deletion forms submitted.
- [ ] Subscriptions configured in Play Console and verified with license testers; RTDN topic connected to production.
- [ ] App Links verified (`assetlinks.json` with app-signing cert).
- [ ] Final E2E script passed on real phone + TV from Play.
- [ ] Staged rollout plan: 10 % → 50 % → 100 % over ≥ 7 days; halt criteria: crash-free users < 99 %, ANR rate > 0.47 %, error spikes, billing failures.
- [ ] Rollback plan: halt rollout in Play, fix forward with a higher `versionCode`; backend feature flags to disable risky features; force-update threshold only for critical issues.
- [ ] Support inbox monitored; FAQ published.

## 10. File structure (new/changed)
```
android/version.properties  android/app-*/build.gradle.kts (release config, signing from env)
android/app-*/proguard-rules.pro  android/app-*/src/main/res/{mipmap-*,drawable*,values*/themes.xml (splash)}
android/app-phone/src/demo/**  android/app-tv/src/demo/** (demo data flavor for screenshots)
.github/workflows/android-release.yml
web/{privacy,terms,support,delete-account}/…
docs/release/{release-checklist.md,signing.md,play-forms.md,e2e-script.md,store-listing/{en,hi}/*.md,screenshots/**}
docs/decisions/ADR-00xx-play-review-account.md
CHANGELOG.md
```

## 11. Acceptance criteria
- [ ] Signed release AABs for phone and TV built in CI and available on the internal track.
- [ ] No keys/passwords in the repo (gitleaks clean); signing works only via CI/env.
- [ ] Final icons, themed icon, splash, TV banner, versioning in place.
- [ ] Legal, support and About complete in app and on the web.
- [ ] Store listing texts, graphics, phone + TV screenshots, policy form answers prepared.
- [ ] Review account mechanism implemented safely (ADR, tests, audit).
- [ ] Final E2E verification passed on real devices from Play; checklist filled with evidence.
- [ ] Rollout and rollback plans documented. Status in CLAUDE.md: **Release candidate**.

## 12. Pitfalls
- R8 can break kotlinx.serialization/Retrofit at runtime only in release — always run the release smoke test.
- Uploading a TV AAB to the phone track (or vice versa) confuses device targeting — keep the form-factor track separate.
- `assetlinks.json` must contain the **Play app signing** certificate fingerprint, not just the upload key.
- Play policy reviewers may reject "video downloader" apps that imply downloading from platforms like YouTube — keep listing, screenshots and demo data clearly about the user's own direct links.
