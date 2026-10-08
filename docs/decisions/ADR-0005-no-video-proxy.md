# ADR-0005 — The backend never stores or proxies video

**Status:** Accepted (Phase 1)

## Context
Carrying video through our servers would dominate cost, create copyright exposure, and make the
backend a bandwidth bottleneck. The TV can reach source servers itself.

## Decision
The only component that touches video bytes is the TV (and the phone's player). The backend
stores links, metadata and state. To classify a URL it may make **bounded** inspection requests
only: `HEAD`, `Range: bytes=0-0`, or the first ≤ 64 KB of an HTML page / ≤ 256 KB of a manifest.
Never a full download, never a relay.

## Consequences
- Backend cost scales with users and links, not with video size.
- Links only the TV can reach (home NAS, IP-bound signed URLs) can't be inspected by the cloud;
  the TV inspects them locally and reports the result (Phase 5).
- Every server-side fetch of a user URL is an SSRF surface and must go through one hardened
  client that blocks private, loopback, link-local, CGNAT, multicast and metadata addresses on
  every redirect hop (Phase 5).
- No DRM circumvention and no extraction around a site's protections; unsupported sources are
  labelled as such.
