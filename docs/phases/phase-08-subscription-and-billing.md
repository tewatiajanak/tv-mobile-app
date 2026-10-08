# Phase 8 — Subscription, Billing & Entitlements

> **Implement this phase only. Do not start Phase 9. End with the phase completion report.**

---

## Prompts to paste

**Prompt A — plan**
```
Read CLAUDE.md, docs/phases/00-architecture-and-conventions.md, docs/phases/phase-08-subscription-and-billing.md
and the latest phase report. We are implementing Phase 8 (Subscription & Billing) only.
Inspect the EntitlementsService stub, every place that calls it (devices, pairing, videos,
downloads, storage), and Android code that reads entitlements. Give me a numbered plan:
schema + seed, entitlement registry and effective-entitlement calculation, subscription state
machine, BillingProvider abstraction with Manual and Google Play providers, RTDN endpoint,
lifecycle cron, APIs, Android Play Billing integration, plan screens, entitlement caching on
devices, and the entitlement boundary test matrix. Do not write code yet.
```

**Prompt B — implement**
```
Go. Implement Phase 8 step by step: schema + seed → entitlement registry/effective calc (replace
the stub without changing call sites) → state machine (pure, table-tested) → Manual provider +
test simulator → Google Play provider + RTDN → lifecycle cron → APIs → Android billing + UI →
tests. Build, test and commit after each step. No limit may be hardcoded in the apps.
```

**Prompt C — verify & report**
```
Run all tests including the full entitlement boundary matrix and the state machine table. Use
the non-production simulator to walk a user through TRIAL → ACTIVE → upgrade → GRACE_PERIOD →
PAST_DUE → recovered → downgrade at renewal → CANCELLED → EXPIRED → refund, showing entitlement
changes on the phone and TV live. Tick acceptance criteria, write docs/phase-reports/PHASE-08-report.md,
update CLAUDE.md, stop.
```

**Prompt D — fix**
```
<paste failure>. Root-cause first, explain briefly, smallest fix, re-run failing + full tests, commit.
```

---

## 1. Goal

Plans, prices, subscriptions, payments, invoices and entitlements are **centrally managed by the
backend**. Apps display plans and limits fetched from the backend and never decide entitlement
themselves. Google Play Billing is integrated behind a provider abstraction; a Manual provider
supports admin grants and testing. Every subscription state and every entitlement boundary is tested.

## 2. Prerequisites
Phases 3–6 (entitlement stub wired into device, pairing, video, storage and download limits).

## 3. Scope
**In:** plan catalogue + seed, entitlement registry, effective entitlements + overrides,
subscription state machine (TRIAL, ACTIVE, PAST_DUE, GRACE_PERIOD, CANCELLED, EXPIRED, PAUSED),
payments/invoices, upgrade/downgrade/renewal/cancel/expiry/grace/failed-payment/refund handling,
BillingProvider abstraction (Manual + Google Play), Play RTDN webhook, reconciliation jobs,
APIs, `SUBSCRIPTION_CHANGED`/`ENTITLEMENTS_CHANGED` events, phone plan & purchase UI, TV plan
display, client entitlement caching, over-limit policy.
**Out:** admin UI for plans/grants (13), notifications about billing (14), family member profiles (future).

---

## 4. Default catalogue (seed — configurable, not code constants)

| Plan | Monthly | Yearly | max_devices | max_tv_devices | max_saved_links | max_active_downloads | max_queued_downloads |
|---|---|---|---|---|---|---|---|
| FREE | ₹0 | — | 2 | 1 | 50 | 1 | 3 |
| PLUS | ₹99 | ₹799 | 4 | 2 | 500 | 2 | 10 |
| PRO | ₹199 | ₹1,599 | 10 | 5 | 5,000 | 4 | 50 |
| FAMILY | ₹299 | ₹2,499 | 10 | 5 | 10,000 | 6 | 100 |

Other entitlements (suggested seed):

| Key | Type | FREE | PLUS | PRO | FAMILY |
|---|---|---|---|---|---|
| `max_storage_destinations` | int | 1 | 2 | 5 | 5 |
| `scheduled_downloads` | bool | false | false | true | true |
| `bandwidth_controls` | bool | false | true | true | true |
| `family_profiles` | bool | false | false | false | true (feature itself is future work) |
| `priority_support` | bool | false | false | true | true |
| `advanced_playback` | bool | false | true | true | true |
| `download_history` | bool | false | true | true | true |

Prices stored as minor units (`9900` INR). Google Play product IDs: subscriptions `plus`, `pro`,
`family`, each with base plans `monthly` and `yearly` (optional `trial-7d` offer). IDs live in
`plan_prices`, not in the app.

---

## 5. Data model (migration `…_billing`)

```prisma
enum SubscriptionStatus { TRIAL ACTIVE PAST_DUE GRACE_PERIOD CANCELLED EXPIRED PAUSED }
enum BillingProviderId { GOOGLE_PLAY MANUAL }
enum BillingInterval { MONTH YEAR }
enum PaymentStatus { PENDING SUCCEEDED FAILED REFUNDED PARTIALLY_REFUNDED CHARGEBACK }

model Plan {
  id String @id @db.Uuid
  code String @unique @db.VarChar(20)          // FREE PLUS PRO FAMILY
  name String @db.VarChar(40)
  description String? @db.VarChar(300)
  isActive Boolean @default(true) @map("is_active")
  isFree Boolean @default(false) @map("is_free")
  sortOrder Int @default(0) @map("sort_order")
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  updatedAt DateTime @updatedAt @map("updated_at") @db.Timestamptz
  prices PlanPrice[]
  entitlements PlanEntitlement[]
  @@map("plans")
}
model PlanPrice {
  id String @id @db.Uuid
  planId String @map("plan_id") @db.Uuid
  interval BillingInterval
  amountMinor Int @map("amount_minor")
  currency String @db.Char(3)
  provider BillingProviderId
  providerProductId String? @map("provider_product_id") @db.VarChar(100)
  providerBasePlanId String? @map("provider_base_plan_id") @db.VarChar(100)
  isActive Boolean @default(true) @map("is_active")
  plan Plan @relation(fields: [planId], references: [id])
  @@unique([provider, providerProductId, providerBasePlanId])
  @@map("plan_prices")
}
model PlanEntitlement {
  planId String @map("plan_id") @db.Uuid
  key String @db.VarChar(50)
  value Json                                    // number | boolean, validated by the registry
  plan Plan @relation(fields: [planId], references: [id])
  @@id([planId, key])
  @@map("plan_entitlements")
}
model Subscription {
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  planId String @map("plan_id") @db.Uuid
  priceId String? @map("price_id") @db.Uuid
  provider BillingProviderId
  providerRef String? @map("provider_ref") @db.VarChar(200)        // Play: SHA-256 of purchaseToken (lookup key)
  providerTokenEnc String? @map("provider_token_enc")              // Play purchaseToken, AES-GCM encrypted
  linkedFromSubscriptionId String? @map("linked_from_subscription_id") @db.Uuid  // upgrade chain
  status SubscriptionStatus
  autoRenew Boolean @default(true) @map("auto_renew")
  trialEndsAt DateTime? @map("trial_ends_at") @db.Timestamptz
  currentPeriodStart DateTime? @map("current_period_start") @db.Timestamptz
  currentPeriodEnd DateTime? @map("current_period_end") @db.Timestamptz
  graceEndsAt DateTime? @map("grace_ends_at") @db.Timestamptz
  pendingPlanId String? @map("pending_plan_id") @db.Uuid           // deferred downgrade
  pendingChangeAt DateTime? @map("pending_change_at") @db.Timestamptz
  cancelledAt DateTime? @map("cancelled_at") @db.Timestamptz
  pausedUntil DateTime? @map("paused_until") @db.Timestamptz
  endedAt DateTime? @map("ended_at") @db.Timestamptz
  version Int @default(1)
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  updatedAt DateTime @updatedAt @map("updated_at") @db.Timestamptz
  @@index([userId, status])
  @@unique([provider, providerRef])
  @@map("subscriptions")
}
model SubscriptionEvent {
  id String @id @db.Uuid
  subscriptionId String @map("subscription_id") @db.Uuid
  userId String @map("user_id") @db.Uuid
  type String @db.VarChar(50)                    // PURCHASED RENEWED IN_GRACE ON_HOLD RECOVERED CANCELED EXPIRED REVOKED PAUSED UPGRADED DOWNGRADE_SCHEDULED REFUNDED …
  fromStatus SubscriptionStatus? @map("from_status")
  toStatus SubscriptionStatus? @map("to_status")
  providerEventId String? @unique @map("provider_event_id") @db.VarChar(200)   // dedupe (Pub/Sub messageId)
  payload Json                                    // sanitized: no tokens
  occurredAt DateTime @map("occurred_at") @db.Timestamptz
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  @@index([subscriptionId, occurredAt])
  @@map("subscription_events")
}
model Payment {
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  subscriptionId String? @map("subscription_id") @db.Uuid
  provider BillingProviderId
  providerPaymentId String? @map("provider_payment_id") @db.VarChar(200)   // Play orderId (e.g. GPA.1234-…..0)
  amountMinor Int @map("amount_minor")
  currency String @db.Char(3)
  status PaymentStatus
  refundedAmountMinor Int @default(0) @map("refunded_amount_minor")
  failureReason String? @map("failure_reason") @db.VarChar(200)
  paidAt DateTime? @map("paid_at") @db.Timestamptz
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  updatedAt DateTime @updatedAt @map("updated_at") @db.Timestamptz
  @@unique([provider, providerPaymentId])
  @@map("payments")
}
model Invoice {
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  paymentId String @unique @map("payment_id") @db.Uuid
  number String @unique @db.VarChar(40)          // VB/2026-27/000123 (sequence per financial year)
  issuedAt DateTime @map("issued_at") @db.Timestamptz
  periodStart DateTime? @map("period_start") @db.Timestamptz
  periodEnd DateTime? @map("period_end") @db.Timestamptz
  lineItems Json @map("line_items")
  subtotalMinor Int @map("subtotal_minor")
  taxMinor Int @map("tax_minor")
  totalMinor Int @map("total_minor")
  currency String @db.Char(3)
  @@map("invoices")
}
model UserEntitlement {                            // overrides / grants
  id String @id @db.Uuid
  userId String @map("user_id") @db.Uuid
  key String @db.VarChar(50)
  value Json
  reason String @db.VarChar(200)
  grantedBy String? @map("granted_by") @db.Uuid  // admin user (Phase 13)
  expiresAt DateTime? @map("expires_at") @db.Timestamptz
  createdAt DateTime @default(now()) @map("created_at") @db.Timestamptz
  revokedAt DateTime? @map("revoked_at") @db.Timestamptz
  @@index([userId])
  @@map("user_entitlements")
}
```
`users` gains `billing_account_ref VARCHAR(64) UNIQUE` = `hmac(BILLING_ACCOUNT_PEPPER, userId)` (base64url,
≤ 64 chars as Play requires). It's the value passed as `obfuscatedAccountId` and lets webhooks find the user.

Raw SQL: partial unique index — one "live" subscription per user:
`CREATE UNIQUE INDEX subscriptions_one_live ON subscriptions(user_id) WHERE status IN ('TRIAL','ACTIVE','PAST_DUE','GRACE_PERIOD','CANCELLED','PAUSED');`

> Tax: for Google Play purchases, Google handles consumer sales tax in many countries; invoices
> record the amounts the provider reports. Confirm GST treatment with an accountant before launch
> and document it in `docs/ops/billing.md`. No tax logic is hardcoded beyond storing provided values.

---

## 6. Entitlements

### 6.1 Registry (`modules/entitlements/registry.ts`)
```ts
export const ENTITLEMENTS = {
  max_devices:              { type: 'int',  min: 1 },
  max_tv_devices:           { type: 'int',  min: 0 },
  max_saved_links:          { type: 'int',  min: 0 },
  max_active_downloads:     { type: 'int',  min: 0 },
  max_queued_downloads:     { type: 'int',  min: 0 },
  max_storage_destinations: { type: 'int',  min: 0 },
  scheduled_downloads:      { type: 'bool' },
  bandwidth_controls:       { type: 'bool' },
  family_profiles:          { type: 'bool' },
  priority_support:         { type: 'bool' },
  advanced_playback:        { type: 'bool' },
  download_history:         { type: 'bool' },
} as const;
```
Unknown keys rejected; seed and admin writes validated against it. Clients receive the map and
must ignore unknown keys (forward compatible).

### 6.2 Effective calculation (`EntitlementsService.getEffective(userId)`)
1. `base` = entitlements of the FREE plan.
2. Find the live subscription. **Entitled statuses:** `TRIAL`, `ACTIVE`, `GRACE_PERIOD`, and
   `CANCELLED` while `now < currentPeriodEnd`. Not entitled: `PAST_DUE` (Play "account hold"),
   `PAUSED`, `EXPIRED`. If entitled → `base` = that plan's entitlements.
3. Apply non-expired, non-revoked `user_entitlements` overrides (override replaces the value).
4. Result `{ plan: { code, name }, status, entitlements, source: "plan|override|free", computedAt }`.
5. Cache in Redis `ent:{userId}` (5 min); invalidate on any subscription/override/plan change;
   on change emit `ENTITLEMENTS_CHANGED` (and `SUBSCRIPTION_CHANGED` when relevant) through `SyncService`.

### 6.3 Usage & over-limit policy
- `GET /subscriptions/me` includes `usage`: `devices`, `tvDevices`, `savedLinks`,
  `activeDownloads`, `queuedDownloads`, `storageDestinations`, and `overLimit: string[]`.
- **Never delete user data on downgrade/expiry.** Over-limit items keep working; **new
  additions are blocked** until usage is under the limit; apps show a banner "You're over your
  FREE plan limits: 3 of 2 devices". Download slots shrink at the next scheduling decision (running
  downloads finish).
- All limit checks remain in `LimitsService` (already wired); they now use real data.

---

## 7. Subscription state machine (`subscription-state.ts`, pure function)

`apply(current, event, now) → { next, sideEffects[] }`

| From | Event | To | Notes |
|---|---|---|---|
| — | `PURCHASED` (trial offer) | TRIAL | `trialEndsAt` |
| — | `PURCHASED` | ACTIVE | payment SUCCEEDED, invoice |
| TRIAL | `RENEWED` (first charge) | ACTIVE | payment, invoice |
| TRIAL | `EXPIRED` / `CANCELED`+trial end | EXPIRED | |
| ACTIVE | `RENEWED` | ACTIVE | new period; apply `pendingPlanId` if `pendingChangeAt ≤ now` |
| ACTIVE | `IN_GRACE` (payment failed) | GRACE_PERIOD | payment FAILED; still entitled until `graceEndsAt` |
| GRACE_PERIOD | `RECOVERED` | ACTIVE | payment SUCCEEDED |
| GRACE_PERIOD | `ON_HOLD` | PAST_DUE | not entitled |
| PAST_DUE | `RECOVERED` | ACTIVE | |
| PAST_DUE | `EXPIRED` | EXPIRED | |
| ACTIVE / GRACE | `CANCELED` (auto-renew off) | CANCELLED | entitled until `currentPeriodEnd` |
| CANCELLED | `RESTARTED` (user re-enabled) | ACTIVE | |
| CANCELLED | period end reached (`EXPIRED`) | EXPIRED | `endedAt` |
| ACTIVE | `PAUSED` | PAUSED | `pausedUntil` |
| PAUSED | `RESUMED`/`RENEWED` | ACTIVE | |
| any live | `REVOKED` / `REFUNDED` (full) | EXPIRED | payment REFUNDED, immediate loss of entitlement |
| any | `PARTIAL_REFUND` | unchanged | payment PARTIALLY_REFUNDED |
| ACTIVE (plan A) | `UPGRADED` (to higher plan) | ACTIVE (plan B) | immediate; old subscription row → EXPIRED with `linked` pointer |
| ACTIVE | `DOWNGRADE_SCHEDULED` | ACTIVE | `pendingPlanId`, `pendingChangeAt = currentPeriodEnd` |

Invalid transitions are logged and ignored (idempotent processing), never thrown to the provider.

---

## 8. Billing providers

```ts
export interface BillingProvider {
  readonly id: BillingProviderId;
  /** Verify a client-submitted purchase and return the normalized subscription state. */
  verifyPurchase(userId: string, input: unknown): Promise<NormalizedSubscription>;
  /** Parse + authenticate a provider webhook, return normalized events (state re-fetched from provider). */
  handleWebhook(req: RawWebhook): Promise<NormalizedBillingEvent[]>;
  /** Periodic reconciliation for one subscription. */
  refresh(sub: Subscription): Promise<NormalizedSubscription>;
}
```

### 8.1 Manual provider
- Used for admin grants (Phase 13), internal testers and tests. Creates/updates subscriptions directly.
- **Simulator (non-production only):** `POST /api/v1/billing/test/simulate`
  `{ userId?, planCode, event: "PURCHASED"|"RENEWED"|"IN_GRACE"|"ON_HOLD"|"RECOVERED"|"CANCELED"|"RESTARTED"|"EXPIRED"|"PAUSED"|"REVOKED"|"REFUNDED"|"UPGRADED"|"DOWNGRADE_SCHEDULED", at? }`.
  Route not registered when `APP_ENV=production` (test asserts 404).

### 8.2 Google Play provider
- Config: `GOOGLE_PLAY_PACKAGE_NAME`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (secret, base64),
  `GOOGLE_PUBSUB_PUSH_AUDIENCE`, `GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL`, `BILLING_TOKEN_ENC_KEY`.
- **Purchase verification** `POST /api/v1/billing/google-play/purchases` `{ productId, purchaseToken }` (auth):
  1. Rate-limit; dedupe by `sha256(purchaseToken)`.
  2. Call Android Publisher `purchases.subscriptionsv2.get(packageName, token)`.
  3. Verify package, `externalAccountIdentifiers.obfuscatedExternalAccountId == hmac(userId)`
     (the app sets this when launching the flow), line item product id ∈ catalogue.
  4. If `linkedPurchaseToken` present → it's an upgrade/downgrade/resubscribe: expire the linked
     subscription row and link the new one.
  5. Map state: `SUBSCRIPTION_STATE_ACTIVE`→ACTIVE (or TRIAL if the line item has a free-trial
     offer phase), `IN_GRACE_PERIOD`→GRACE_PERIOD, `ON_HOLD`→PAST_DUE, `PAUSED`→PAUSED,
     `CANCELED`→CANCELLED, `EXPIRED`→EXPIRED, `PENDING`→ keep as pending (no entitlement; show
     "Payment pending" — common with UPI in India), `PENDING_PURCHASE_CANCELED`→ discard.
  6. **Acknowledge** with `purchases.subscriptions.acknowledge` if `acknowledgementState` is
     pending (Play refunds unacknowledged purchases after 3 days). Retry acknowledgement in a job.
  7. Persist subscription, events, payment (`latestOrderId`), invoice; emit events.
- **RTDN webhook** `POST /api/v1/billing/google-play/rtdn` (public, Pub/Sub push):
  verify the Pub/Sub OIDC JWT (`Authorization: Bearer`, issuer Google, audience =
  `GOOGLE_PUBSUB_PUSH_AUDIENCE`, email = configured service account) → decode
  `message.data` (base64 JSON `subscriptionNotification {notificationType, purchaseToken, subscriptionId}`)
  → dedupe by Pub/Sub `messageId` → **re-fetch** state with `subscriptionsv2.get` (never trust the
  notification alone) → state machine. Always return 2xx after persisting (or 5xx to let Pub/Sub retry on transient errors). Handle `testNotification`.
- **Refunds/voids:** daily job calls `voidedpurchases.list` → `REFUNDED`/`REVOKED`.
- **Reconciliation:** daily job refreshes every live Play subscription; hourly job for those
  in GRACE/PAST_DUE/CANCELLED near period end.

### 8.3 Lifecycle cron (every 15 min)
- CANCELLED and `currentPeriodEnd < now` → EXPIRED.
- GRACE_PERIOD and `graceEndsAt < now` and provider = MANUAL → PAST_DUE.
- Apply due `pendingPlanId` for MANUAL subscriptions (Play applies its own and notifies).
- TRIAL ended without conversion (MANUAL) → EXPIRED.
- After any change: invalidate entitlements cache + emit events.

---

## 9. API contract

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/subscriptions/plans` | public | active plans with prices (`amountMinor`, `currency`, `interval`, `provider`, `productId`, `basePlanId`) and entitlements |
| GET | `/api/v1/subscriptions/me` | user | `{ subscription|null, effectivePlan, status, entitlements, usage, overLimit, manageUrl }` (`manageUrl` = Play subscription center deep link for Play subs) |
| GET | `/api/v1/entitlements` | user | effective entitlements (replaces the stub, same shape + `status`) |
| POST | `/api/v1/billing/google-play/purchases` | user | verify & link a Play purchase |
| POST | `/api/v1/billing/google-play/rtdn` | Pub/Sub OIDC | webhook |
| POST | `/api/v1/subscriptions/me/cancel` | user | MANUAL subs only (Play subs are cancelled in Play → returns `manageUrl`) |
| GET | `/api/v1/subscriptions/me/payments` | user | paginated payments |
| GET | `/api/v1/subscriptions/me/invoices` | user | paginated invoices; `GET …/invoices/{id}` detail (PDF later) |
| POST | `/api/v1/billing/test/simulate` | user (non-prod only) | simulator |

New error codes: `PURCHASE_INVALID`, `PURCHASE_ACCOUNT_MISMATCH`, `PURCHASE_ALREADY_LINKED` (token
linked to another user), `BILLING_PROVIDER_UNAVAILABLE`.

---

## 10. Android tasks

### 10.1 Shared
- `EntitlementsRepository`: cached effective entitlements in DataStore/Room with `fetchedAt`;
  refresh on login, app foreground, `ENTITLEMENTS_CHANGED`/`SUBSCRIPTION_CHANGED`; exposes
  `Flow<Entitlements>`. **Remove every hardcoded limit** (search the codebase; the report lists what was removed).
- `LimitErrorPresenter`: maps `ENTITLEMENT_LIMIT` (`details.entitlement`, `limit`, `current`) to
  a message + "See plans" CTA.
- TV download engine reads `max_active/max_queued` from `EntitlementsRepository` (offline: cached
  values valid 7 days, then FREE defaults from the last fetched FREE plan).

### 10.2 Phone — plans & purchase
- **PlansScreen**: cards built from `/subscriptions/plans` (monthly/yearly toggle, savings
  computed from data, entitlement comparison table generated from the registry with human labels
  in `strings.xml`), current plan highlighted, usage bars from `/subscriptions/me`.
- **Play Billing** (`core:billing` or `app-phone/billing`): latest Play Billing Library;
  `BillingClientWrapper` with reconnection; `queryProductDetails` for product IDs returned by the
  backend; `launchBillingFlow` with `setObfuscatedAccountId(hmacUserId)` (value provided by
  `/subscriptions/me`), offer token for the chosen base plan; for upgrades/downgrades
  `SubscriptionUpdateParams` with the old purchase token and replacement mode
  (upgrade: `CHARGE_PRORATED_PRICE`; downgrade: `DEFERRED`).
- `onPurchasesUpdated`: `PURCHASED` → POST token to backend → wait for `SUBSCRIPTION_CHANGED`
  (or poll `/subscriptions/me` 3×) → success screen; `PENDING` → "Payment pending — we'll unlock
  your plan when it completes"; `USER_CANCELED` → nothing; errors mapped.
- **Restore/recovery:** on app start and on PlansScreen, `queryPurchasesAsync(SUBS)` and send any
  purchase the backend doesn't know (or not acknowledged) to the backend. **The app never
  acknowledges** — the backend does.
- "Manage subscription" opens `manageUrl` (Play subscription center).
- Payments & invoices list in Settings → Subscription.
- Non-production builds: hidden "Billing simulator" debug screen calling `/billing/test/simulate`.

### 10.3 TV
- Settings → Subscription: plan, status, renewal date, entitlements/usage; "Upgrade from your
  phone" with a QR to `https://<domain>/plans` (opens the phone app's PlansScreen via App Link).
  (In-TV purchasing is deferred; document the reason: phone purchase UX is far better.)
- Limit errors (devices, downloads, storage) show the message + QR CTA.

---

## 11. Tests

### State machine (table-driven, pure)
Every row in §7 plus: duplicate events (idempotent), out-of-order events (older `occurredAt`
ignored), invalid transitions ignored with a log, upgrade chain, deferred downgrade applied at renewal.

### Entitlement boundary matrix (e2e, generated)
For each plan × each int entitlement: create usage at `limit − 1` → add succeeds; at `limit` → add
returns `ENTITLEMENT_LIMIT` with correct `details`; after upgrade → succeeds; after downgrade →
existing kept, new blocked, `overLimit` reported. Bool entitlements: feature endpoints (e.g.
scheduling in Phase 12, hybrid seek flag) reflect true/false. Overrides beat plans; expired
overrides ignored. Status matrix: entitled vs not for each `SubscriptionStatus`.

### Provider tests
- Google Play: mocked Android Publisher API responses (fixtures for each `subscriptionState`,
  trial, linked token, pending, acknowledged/not); RTDN with valid/invalid/missing OIDC tokens
  (sign test JWTs with a local key and inject the JWKS); duplicate `messageId`; account mismatch;
  token already linked to another user; acknowledgement retry.
- Voided purchases job → refund state.
- Simulator route absent in production config.

### Android
- `EntitlementsRepositoryTest` (cache, refresh on event, offline expiry), `PlansViewModelTest`
  (data-driven cards, no constants), `BillingClientWrapperTest` with a fake BillingClient
  (purchased, pending, canceled, error, upgrade params), `LimitErrorPresenterTest`.
- Use Play's static test product IDs / license testers for manual Play testing (internal testing track).

---

## 12. File structure (new/changed)
```
backend/prisma/seed.ts (plans, prices, entitlements)
backend/src/modules/entitlements/{registry.ts,entitlements.service.ts (real),limits.service.ts,overrides.service.ts}
backend/src/modules/subscriptions/{subscriptions.module.ts,subscriptions.controller.ts,subscriptions.service.ts,subscription-state.ts,lifecycle.cron.ts,invoice-number.ts,dto/*}
backend/src/modules/billing/{billing.module.ts,billing-provider.ts,manual.provider.ts,simulator.controller.ts,google-play/{google-play.provider.ts,publisher.client.ts,rtdn.controller.ts,pubsub-auth.ts,state-mapper.ts,voided.job.ts,reconcile.job.ts}}
backend/test/{billing.e2e-spec.ts,entitlements-matrix.e2e-spec.ts,subscription-state.spec.ts}
android/core/data/…/{EntitlementsRepository.kt,SubscriptionRepository.kt,LimitErrorPresenter.kt}
android/app-phone/…/billing/{BillingClientWrapper.kt,PlansScreen.kt,PlansViewModel.kt,PurchaseResultScreen.kt,PaymentsScreen.kt,BillingSimulatorScreen.kt(debug)}
android/app-tv/…/settings/TvSubscriptionScreen.kt
docs/ops/billing.md (Play Console setup: products, base plans, RTDN topic, service account permissions, license testers)
```

## 13. Security requirements
- Backend is the only authority; client purchase data is only a token to verify.
- Purchase tokens encrypted at rest; only their hash is used for lookups; never logged.
- `obfuscatedExternalAccountId` check prevents token replay across accounts.
- RTDN authenticated (OIDC JWT); idempotent by message id; state always re-fetched from Google.
- Simulator compiled in but unreachable in production (route not registered + test).
- Service-account JSON only via secret env; principle of least privilege in Play Console (financial data + manage orders only).

## 14. Acceptance criteria
- [ ] Plans/prices/entitlements come from the DB seed; changing a value in the DB changes app behaviour without an app release.
- [ ] No hardcoded limits remain in Android code (grep evidence in the report).
- [ ] Effective entitlements correct for every status; overrides work; cache invalidates and devices update live.
- [ ] Full state machine table passes, including idempotency and out-of-order events.
- [ ] Upgrade (immediate), downgrade (at renewal), renewal, cancellation (access until period end), expiry, grace, on-hold, recovery, pause, refund/revoke all work via the simulator end to end.
- [ ] Google Play verification, acknowledgement, RTDN and voided-purchase handling implemented and tested with mocks; Play Console setup documented.
- [ ] Over-limit policy: nothing deleted; new additions blocked with clear UI.
- [ ] Payments and invoices recorded with sequential invoice numbers.
- [ ] Entitlement boundary matrix passes for all plans.
- [ ] All tests pass; OpenAPI + docs updated.

## 15. Pitfalls
- Unacknowledged Play purchases are auto-refunded after 3 days — acknowledgement must be retried until it succeeds.
- Upgrades create a **new** purchase token (`linkedPurchaseToken`); forgetting to retire the old one double-counts subscriptions.
- `PENDING` purchases (UPI/cash) are common in India — don't grant entitlement until ACTIVE.
- RTDN can arrive before the app posts the token — the webhook path must be able to create the subscription row on its own, matched to the user via `obfuscatedExternalAccountId`.
- Keep the FREE plan as data too; never infer FREE limits from code.
