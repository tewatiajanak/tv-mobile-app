# ADR-0003 — One applicationId for phone and TV

**Status:** Accepted (Phase 1)

## Context
The phone and TV apps are separate Gradle modules with different UIs, but users think of them as
one product. Google Play supports several form factors under one listing.

## Decision
- Both apps use `applicationId = "com.videobridge.app"` and ship as one Play listing: the phone
  AAB on the main track, the TV AAB on the Android TV form-factor track.
- Flavors add a suffix so builds can be installed side by side: `dev` → `.dev`,
  `staging` → `.staging`, `prod` → none.
- Every AAB in one listing needs a unique `versionCode`:
  `versionCode = VERSION_CODE_BASE * 10 + 1` (phone) or `+ 2` (TV), with the base in
  `android/version.properties`. The convention plugin applies this.
- Code namespaces stay distinct: `com.videobridge.phone`, `com.videobridge.tv`.

## Consequences
- One listing, one set of reviews, one subscription catalogue; a purchase is visible to both.
- A phone and TV build of the same flavor **cannot be installed on the same device** (same
  package name) — irrelevant in practice, since they target different devices.
- Uploading an AAB to the wrong track confuses device targeting (guarded in Phase 18).

## Alternative considered
Two package names and two listings (`…app` and `…app.tv`): fully independent release cycles and
no versionCode scheme, but duplicated listings, separate ratings, and Play Billing purchases that
are not shared across the two apps. Rejected; switch here, early, if that trade-off changes.
