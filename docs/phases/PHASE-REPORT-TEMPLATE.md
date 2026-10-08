# Phase XX — <Title> — Completion Report

**Date:** YYYY-MM-DD  **Branch / commits:** `phase-XX-...` (`abc1234..def5678`)

## 1. Implementation summary
3–8 sentences: what now works end to end, in user terms.

## 2. Files created / modified
| Path | Change | Why |
|---|---|---|
| `backend/src/modules/...` | created | ... |

## 3. Database changes
- Migration(s): `prisma/migrations/2026..._name` — tables/columns/indexes/enums added or changed.
- Backfills / data changes:
- Rollback / forward-fix notes:

## 4. API changes
| Method | Path | Auth | Change |
|---|---|---|---|
- WebSocket events added/changed:
- `docs/api/openapi.json` regenerated: yes/no

## 5. Android changes
- Phone:
- TV:
- New permissions / manifest entries:
- Room schema version: N → M (migration provided: yes/no)

## 6. Tests and build results
Paste the **actual** command output summaries:
```
backend: npm run lint ✔  npm test  → X passed, 0 failed  npm run test:e2e → Y passed
android: ./gradlew lint testDevDebugUnitTest :app-phone:assembleDevDebug :app-tv:assembleDevDebug ✔
```
Coverage of new code (if measured):

## 7. Acceptance criteria
Copy the phase's acceptance checklist and tick each item. For any unticked item, say why.

## 8. Manual testing checklist
Steps the human should perform on a real phone/TV (or emulator), with expected results.

## 9. Security notes
New attack surface, mitigations added, anything to revisit in Phase 15.

## 10. Known issues / deferred items
| Issue | Impact | Planned phase |
|---|---|---|

## 11. Library versions chosen / changed

## 12. Next-phase recommendations
What the next phase should know or adjust. **Do not start it.**
