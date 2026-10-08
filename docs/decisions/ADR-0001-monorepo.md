# ADR-0001 — Single repository

**Status:** Accepted (Phase 1)

## Context
VideoBridge is a backend, two Android apps, later an admin web app, plus infrastructure and
documentation. They share contracts (REST, WebSocket events, error codes) that change together.

## Decision
One repository with top-level `backend/`, `android/`, `admin-web/` (Phase 13), `infra/`, `docs/`.
CI workflows are path-filtered so a change builds only what it touches.

## Consequences
- A contract change and both sides of it land in one commit and one review.
- `docs/api/openapi.json` and the event schemas sit next to the code that must obey them.
- The repo grows large and mixes toolchains (Node, Gradle); path filters keep CI time bounded.
- Access control is all-or-nothing; acceptable for a small team.
