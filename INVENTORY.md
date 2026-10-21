# Order tranche inventory (2026-10-05)

Scope: domain equivalence slices on `refactor/order-core`. References below are
pre-extraction host line numbers. Authority: `docs/platform/product/features/order-lifecycle.md`,
`docs/workflows/order_lifecycle_flow.md`, consolidation plan, and executable host behavior.
No plan, topic, schema, flag or business-policy changes are authorized.

| Workflow / entrypoint (under order-service/src/main/java/com/delivery/order_service/) | Decisions and guarantees before extraction |
| --- | --- |
| Preview: controller/OrderController.java:54; service/CheckoutPreviewService.java:107 | USER admission; canonical Restaurant/menu facts, livestream/flash/voucher gating and incompatibilities, duplicates, prices, shipping and ETA. Read/remote calls; CheckoutQuoteIssuer persists a principal/input/pricing-bound quote with expiry. |
| Create: controller/OrderController.java:67; service/impl/OrderServiceImpl.java:149,165,216,277 | Customer admission before bounded concurrency permit; scoped idempotency lease before preflight, exact completed replay read with ownership; quote owner/input/expiry/reprice, canonical Restaurant/menu validation in OrderValidationService.java:76; no client monetary authority. Preflight precedes TransactionTemplate write. Receipt re-claim, zero-price shell flush, inventory/flash reserves, subtotal/shipping/vouchers, item/money snapshots, inventory commit, quote lock/consume, receipt completion and order.created outbox. Remote reservation failure releases identical IDs; TTL is recovery fence. Optional rails/default flags remain host configuration. |
| Quote: service/CheckoutQuoteService.java:47,83 | Read/reprice without write lock across HTTP; changed price issues replacement quote and PRICE_CHANGED; final consume pessimistically locks owner/expiry/unused quote inside create transaction. |
| Create retry: service/OrderCreateIdempotencyService.java:43,74,99,124,140 | Unique principal+key, SHA-256 effective command, H2/Postgres atomic insert, processing token/lease expiry and in-progress conflict; final claim requires current live owner. Completed receipt binds order; failure releases only owned incomplete lease. |
| Saga status: listener/SagaCommandListener.java:57; service/SagaOrderCommandProcessor.java:36,52,68,105 | JSON/UUID/order identity admission, exact sagaStatus routing (SHIPPER_FOUND maps WAIT_SHIPPER_CONFIRM); raw-payload SHA-256 inbox claim BEFORE pessimistic order lock. Exact event replay returns false without order mutation; contradictory identity/payload IllegalArgumentException. Nonpositive sequence accepted only at cursor 0; <=cursor sequenced command commits receipt without mutation; next sequence updates cursor before status handler; gap throws retryable host exception and rolls back receipt/cursor/state. ACK after transactional processor returns; poison IllegalArgumentException excluded from retry, other errors wrapped. |
| Delivery progression: service/impl/OrderEventServiceImpl.java:51; entity/OrderStatus.java:36 | Locked order, simulation-context equality before status mapping; strict canonical state table, same status no save/notes/timestamp; PENDING→CONFIRMED→FINDING_SHIPPER bridges cross-topic confirmation race; finding clears shipper. Host saves/timestamps/notes, no new event here. |
| Assignment / no shipper: service/impl/OrderEventServiceImpl.java:228; service/impl/OrderServiceImpl.java:854 | Assignment validates context and positive shipper; ASSIGNED/PICKED_UP/DELIVERING/DELIVERED exact shipper replay no-op, other shipper conflicts. No-shipper mutates only CONFIRMED/FINDING_SHIPPER/WAIT_SHIPPER_CONFIRM, otherwise no-op; overwrite retry note, retain distinct terminal state, enqueue refund-eligible in same transaction. Host wraps all no-shipper failures in IllegalStateException. |
| Restaurant decision: listener/RestaurantEventListener.java:44,71; service/impl/OrderEventServiceImpl.java:149,185 | Lock order before decision receipt; validate event/actor/restaurant, serialized-event fingerprint, unique event/order, exact replay no-op and contradictory replay fail-closed. Late confirmation records receipt without regressing advanced state; reject requires PENDING, publishes cancellation snapshot/outbox. ACK follows transaction return. |
| Payment compatibility: listener/PaymentEventListener.java:34,60; service/impl/OrderEventServiceImpl.java:102,121 | Locked order; COD completion does nothing/failure ignored; non-COD pending completion confirms, failure transitions CANCELLED and enqueues typed system cancellation. Not active online checkout; preserve compatibility rail. |
| Cancellation/refund boundary: controller/OrderController.java:174; service/impl/OrderServiceImpl.java:748,790,821,834; service/OrderEventPublisher.java:75,98 | Pessimistic order lock, principal ownership or default-off enforcement legacy fallback; ADMIN bypasses eligibility guard but still must pass transition table. Customer/owner pre-pickup only. Exact cancelled actor+reason replay no save/event; conflicting replay fails. Persist cancellation and typed source/reason immutable money/reservation snapshot in mandatory transactional outbox. No provider refund here; Settlement owns downstream gated eligibility/provider decisions. |
| Outbox: service/OrderOutboxService.java:32,39; service/OrderOutboxRelay.java:112,145; service/OrderOutboxLeaseService.java:26,46,61 | MANDATORY enqueue with stable event ID/trace/correlation; short row-lock lease claim, Kafka outside DB transaction, token-fenced SENT/failure update, expiry reclaim, bounded retries/DEAD. Publish-before-mark can duplicate: at-least-once, consumers must use receipts. Payload rehydration preserves contract type headers. |
| Reads: controller/OrderController.java:103,113,124,151,163; service/impl/OrderServiceImpl.java:594,615,631,669,680,710 | Read-only paged queries; ADMIN unrestricted detail/global status/all; USER own principal; restaurant owner creator principal; unmigrated legacy fallback only while enforcement off; SHIPPER detail compares legacy userId to stored shipperId. No external identity hot-path lookup. Restaurant list service helper itself has no authorization (not public controller route). |
| Internal eligibility: controller/InternalOrderController.java:29,51 | Shared Internal-Token fails closed; rating requires legacy user+restaurant+DELIVERED; restaurant decision requires restaurant+PENDING under pessimistic lock, released when request transaction ends. |

## Ordered remaining slices

1. Complete Saga status application orchestration through transaction/store/receipt ports; preserve receipt-before-order locking, stale receipt commit, gap rollback and ACK proof.
2. Extract restaurant/payment transaction/store orchestration through application ports; domain lifecycle/receipt decisions and cross-topic convergence/replay integration proof are now extracted (slice 2 below).
3. Ownership/read and cancellation/refund-intent domain policies are extracted (slice 3 below), preserving principal fallback and exception precedence; application transaction/store orchestration remains for later consolidation.
4. Create/preview admission, canonical fact checks and availability, pricing,
   shipping distance/rounding, serviceability/ETA and quote lifecycle decisions
   are extracted (slice 4 and remainder below), preserving distinct error
   precedence. HTTP/transaction/store orchestration remains for slices 5–6.
5. Create/quote/idempotency/reservation orchestration and compensation/lease recovery are extracted (slice 5 below), with DB race and remote ambiguous-failure proof.
6. Extract outbox lease/retry application orchestration; relocate adapters/composition to infrastructure and entrypoint/config to boot; remove host only after packaged HTTP/Kafka/Postgres recovery proof and inventories/packaging updates (outside this slice).

## Observed pre-existing discrepancies (preserved)

- Cancellation comment promises ADMIN any-state cancellation, but the shared transition table rejects PICKED_UP, DELIVERING and terminal states. Do not change either contract in this extraction.
- SHIPPER read ownership compares authenticated legacy user ID with shipperId, while product overview names shipper.id canonical. Identity migration needs separate authority/proof.
- No-shipper handler does not validate simulation context or set updatedAt; refund snapshot uses existing updatedAt if present. Delivery/assignment handlers do validate context. Preserved, not repaired here.
- The consolidation plan records an earlier Docker-enabled SagaOrderKafkaPostgresIntegrationTest baseline failure (missing retryKafkaTemplate); current execution result must distinguish available Docker proof from skipped classes.

## Slice 1 implementation and equivalence evidence

- `order/domain` inherits the build-parent JaCoCo check with 85% LINE and BRANCH
  minima; root reactor lists it before the unchanged `order-service` host and
  host directly depends on `order-domain:1.0.0-SNAPSHOT`.
- Domain owns canonical enum/aliases/transition table, delivery status mapping,
  confirmation bridge, assignment/no-shipper decisions, sequence admission and
  command admission/exact receipt replay. No framework/host/contracts dependency.
  No application-api/application module is created: transaction/store/receipt
  orchestration remains a future coherent use-case extraction, not an empty layer.
- Host enum remains a persistence/DTO mapping facade. Invalid external enum text
  is translated to preserve the original fully qualified host enum error message.
  Sequence GAP translates back to the original SagaOrderSequenceGapException.
- Receipt lookup/insert/fingerprint, pessimistic query, transaction annotations,
  cursor assignment before handler, timestamps, notes, shipper writes, save calls,
  refund/cancellation events, outbox enqueue, ACK/retry routing, schema, wire types,
  messages and feature flags remain host-owned and unchanged.
- Exhaustive domain tests cover all 100 source/target state pairs, self/null,
  terminal states, canonical/legacy aliases, case-sensitive delivery mappings,
  bridge/no-op paths, all assignment/replay/no-shipper states, every receipt
  identity field/validation precedence, and sequence legacy/stale/next/gap bounds.
- Added H2 integration proof exercises actual proxied processor/domain/JPA:
  receipt+cursor+bridge commit, stale receipt commit without effects, gap and
  invalid-transition rollback, identical gap retry after predecessor, and legacy
  acceptance before fencing/rejection after fencing. Original listener, event,
  receipt, converter and PostgreSQL concurrency assertions are retained.

## Slice 1 validation result / historical parent handoff

Branch `refactor/order-core` already existed; HEAD and main both pointed to
`3bcf2c7e7f6407a222e1cc820a6819a8f88e57cd`. No git write commands or docs/plans edits.

- `mvn -B -pl :order-domain -am clean verify`: exit 0; 8 tests,
  zero failures/errors/skips. Domain JaCoCo: LINE 80/80 (100%), BRANCH 99/99 (100%);
  both 85% gates passed. Log: `/tmp/order-domain-verify.log`.
- Required `mvn -B -pl :order-service -am clean verify`, with sandbox escalation:
  exit 1; Order host 161 tests, zero failures, **one error**, zero skips.
  Sole error: existing `SagaOrderKafkaPostgresIntegrationTest` cannot inject
  `KafkaTemplate<String,String>` qualified `retryKafkaTemplate`. This reproduces
  the baseline defect recorded in consolidation plan lines 137–142. Prometheus
  and both PostgreSQL concurrency classes passed. Log:
  `/tmp/order-reactor-verify-unsandboxed.log`.
- First sandboxed invocation hit local-server `Operation not permitted` and
  skipped `OrderCreateIdempotencyPostgresConcurrencyTest`,
  `SagaOrderKafkaPostgresIntegrationTest`, and
  `SagaOrderCommandPostgresConcurrencyTest` (one test each). Escalated invocation
  resolved the environment restriction and ran all three: **no Docker-only skips
  in the definitive full run**. Log: `/tmp/order-reactor-verify.log`.
- Focused `mvn -B -pl :order-service -am verify
  -Dtest=OrderStatusTest,SagaStatusPolicyTest,SagaCommandIdentityTest,SagaCommandReceiptServiceTest,SagaCommandReceiptTransactionIntegrationTest,SagaCommandListenerTest,OrderEventServiceTest,SagaOrderCommandPostgresConcurrencyTest
  -Dsurefire.failIfNoSpecifiedTests=false`: exit 0; domain 8 + host 43 tests,
  zero failures/errors/skips; host packaged successfully. Log:
  `/tmp/order-focused-verify.log`. This is focused equivalence evidence, not a
  substitute for the failing full acceptance check.
- Final transaction test clarifies that a gap retries the identical status/event
  after its predecessor; final focused non-Docker verification is recorded in
  `/tmp/order-focused-final-verify.log`: exit 0, domain 8 + host 42 tests,
  zero failures/errors/skips; both coverage checks and host packaging passed.
- `git diff --check` passes; source inspection finds no Spring, Jakarta, Jackson
  or Lombok imports in domain production code.

**Historical NEEDS_CONTEXT (fixture repair authorized and handled in slice 2 below):** implementation and focused equivalence proof are complete,
but full zero-error acceptance cannot be met while preserving known defects.
Parent must authorize a separate repair of Kafka test/composition wiring, or
handle the baseline acceptance decision. This task does not fix that defect.

## Slice 2: restaurant/payment lifecycle and receipt decisions

- `RestaurantPaymentPolicy` in domain owns restaurant event admission in the
  original validation order, every confirmation/rejection state decision,
  cancellation reason defaults, non-COD payment compatibility decisions and
  exact restaurant receipt replay binding/conflict rules. Host
  `OrderEventServiceImpl` delegates these rules directly. No application module
  is needed for this domain slice; transaction/store orchestration remains in
  the host and is still required before the service consolidation is complete.
- Pessimistic order lock before admission/receipt lookup, serialized DTO SHA-256,
  event-before-order receipt lookup, insert/flush before status decisions,
  transactional rollback, timestamps/notes, immutable compensation snapshots,
  outbox enqueue and listener ACK order are retained. No production defaults,
  flags, topics, schema or wire contracts change.
- Domain tests enumerate every status for restaurant confirmation/rejection and
  every status across COD/online/unknown/empty/null payment methods; admission
  precedence, every receipt identity field, null/empty reason semantics and
  the existing payment-failure transition table are covered.
- Real proxied H2/JPA integration tests prove both restaurant/Saga topic arrival
  orders converge, exact replay preserves timestamps/notes, changed payload and
  opposite/new-event decisions fail closed, rejection writes one immutable
  compensation snapshot, ineligible decisions roll back receipts, receipt +
  cancellation + outbox roll back together and retry, COD no-ops, online
  completion replay, system cancellation and post-pickup failure rollback.

### Kafka fixture repair and newly observable pre-existing discrepancy

- The old test injection at
  `order-service/src/test/java/com/delivery/order_service/listener/SagaOrderKafkaPostgresIntegrationTest.java:100`
  requested `KafkaTemplate<String,String>` named `retryKafkaTemplate`. Order's
  shared production configuration exposes only `commonKafkaTemplate` /
  `kafkaTemplate` with `KafkaTemplate<String,Object>`
  (`platform/kafka-starter/src/main/java/com/delivery/platform/kafka/CommonKafkaProducerConfig.java:55`).
  Its producer uses JsonSerializer (line 47). This is a stale test dependency,
  not a missing profile/property or a defect requiring new production beans.
- The test now injects the existing shared template, sends the same command text
  through its real JSON transport, and decodes the JSON-encoded DLT string before
  comparing the entire original payload. No bean/serializer replacement or
  production configuration modification is introduced.
- Docker execution also exposed a stale DLT assertion: Order has no enabled
  RetryableTopic infrastructure, so the active shared factory's error handler
  recovers to the default `.DLT`, not the annotation's `.order.DLT`
  (`CommonKafkaConsumerConfig.java:97`, `CommonKafkaErrorHandler.java:22`).
  The fixture creates/asserts the actual `.DLT` with both source partitions.
  The annotation/runtime retry and owner-DLT discrepancy is preserved for a
  separately authorized platform/runtime decision; this slice does not enable
  nonblocking retries or change poison-message retry classification.
- Initial raw-string fixture experiment failed before any receipt: shared JSON
  deserialization did not deliver the expected String command. Switching to the
  existing shared producer proved receipt/replay convergence; that run then
  failed only waiting for the stale `.order.DLT` expectation. Logs:
  `/tmp/order-slice2-clean-verify.log`, `/tmp/order-kafka-wiring-focused.log`.

### Slice 2 final validation

- Required `mvn -B -pl :order-service -am clean verify`: exit 0, BUILD SUCCESS;
  completed 2026-10-05 22:30:49 +07:00. Log:
  `/tmp/order-slice2-final-clean-verify.log`.
- Domain: 12 tests, zero failures/errors/skips. JaCoCo LINE 107/107 (100%) and
  BRANCH 132/132 (100%); both 85% gates passed. Host: 168 tests, zero
  failures/errors/skips; executable Spring Boot JAR packaged successfully.
- Docker-only skips: **none**. Actual
  `SagaOrderKafkaPostgresIntegrationTest` (1 test),
  `OrderCreateIdempotencyPostgresConcurrencyTest` (1 test), and
  `SagaOrderCommandPostgresConcurrencyTest` (1 test) all passed. PostgreSQL
  migrations, two Kafka replica groups, historical replay, contradictory
  command recovery to the actual DLT and source-offset recovery all ran.
- Initial focused domain/lifecycle validation passed: 4 new domain tests and
  25 host tests (19 existing + 6 initial transaction cases), zero
  failures/errors/skips; `/tmp/order-slice2-focused.log`. The final clean run
  includes the seventh transaction case for post-pickup payment failure.
- `git diff --check` passes; domain production remains framework-independent.
  All edits are under `order/` and `order-service/`; no git writes,
  `docs/plans/` changes or production-default changes.

**Complete for the authorized domain slice and fixture repair.** Remaining
application port/transaction orchestration and the observed inactive
RetryableTopic/owner-DLT discrepancy are explicitly retained, not repaired by
this equivalence slice.

## Slice 3: ownership/read and cancellation/refund intent

- Framework-free `OrderOwnershipPolicy` owns detail/cancellation role admission,
  principal ownership, gated fallback for unmigrated customer/creator rows,
  legacy SHIPPER detail ownership and list admission/denial precedence. The
  existing restaurant-owner wire role is `SHOP_OWNER`. Host maps access outcomes
  to the original `AccessDeniedException` messages and fallback metric labels;
  query execution/paging/mapping and enforcement configuration remain host-owned.
- `OrderCancellationPolicy` owns exact actor/reason replay, pre-pickup eligibility,
  typed actor cancellation source/reason and system no-shipper refund intent/reason
  defaults. It does not decide Settlement/provider refund eligibility. The host
  retains lock → permission → exact replay → eligibility → canonical transition
  → mutation/timestamp → save → metric → outbox → response ordering. ADMIN only
  bypasses eligibility; the canonical transition still rejects post-pickup and
  non-cancelled terminal states. Exact replay keeps the saved timestamp and emits
  no new event. Legacy replay fallback/metrics and null/empty reason semantics
  remain unchanged.
- Existing transaction annotations, snapshots, topics, event types, configuration,
  DTOs and schemas are unchanged. No application ports/modules were introduced:
  this follows the prior domain-policy slice pattern; transaction/store/outbox
  orchestration remains host-owned for later consolidation.
- Domain tests exhaust role/principal/legacy/enforcement/read-vs-cancel combinations,
  every cancellation status, every replay identity/reason combination, typed
  intents and no-shipper reason defaults. Host regressions preserve ADMIN's
  transition discrepancy, SHIPPER's legacy ID discrepancy, SHOP_OWNER wire role,
  fallback metrics, permission-before-replay/status and missing-order precedence,
  list denial-before-query/status parsing. Real proxied H2/JPA tests prove
  cancellation/snapshot/outbox commit, exact replay/conflict and atomic rollback
  followed by retry; existing lock/save/event ordering proof is retained.
- No new business/runtime defect observed. Existing documented ADMIN, SHIPPER,
  no-shipper simulation/timestamp and inactive RetryableTopic/owner-DLT
  discrepancies remain preserved. Tests also retain existing malformed/null
  legacy-ID `NullPointerException` behavior rather than introducing new admission
  semantics; persisted legacy IDs are expected to be non-null.
- Filesystem changes only, under `order/` and `order-service/`; no git commands,
  `docs/plans/` edits, POM changes or coverage-gate reductions.

### Slice 3 final validation

- Required `mvn -B -pl :order-service -am clean verify` with sandbox escalation
  for local server/Docker integration access: exit 0, **BUILD SUCCESS**.
  Log: `/tmp/order-slice3-clean-verify.log`.
- Domain: 18 tests, zero failures/errors/skips; JaCoCo LINE 147/147 (100%) and
  BRANCH 220/220 (100%). Both unchanged 85% gates passed. Host: 176 tests,
  zero failures/errors/skips; executable Spring Boot JAR packaged successfully.
- Docker-only skips: **none**. `OrderCreateIdempotencyPostgresConcurrencyTest`,
  `SagaOrderKafkaPostgresIntegrationTest`, and
  `SagaOrderCommandPostgresConcurrencyTest` each ran and passed (one test each).
- Initial `mvn -B -pl :order-domain -am clean verify`: exit 0, all domain tests
  and 85% checks passed; `/tmp/order-slice3-domain.log`. Initial focused
  `mvn -B -pl :order-service -am verify
  -Dtest=OrderOwnershipPolicyTest,OrderCancellationPolicyTest,OrderServiceCanonicalPricingTest,OrderOutboxTransactionIntegrationTest,OrderEventPublisherTopicConfigurationTest
  -Dsurefire.failIfNoSpecifiedTests=false`: exit 0; domain 6 + host 34 tests,
  zero failures/errors/skips; `/tmp/order-slice3-focused.log`. This preceded the
  final SHOP_OWNER regression; the definitive clean run includes that regression
  and the final production policies.
- Final source inspection confirms no framework imports in domain production;
  no new trailing whitespace in the added/changed blocks. No git-based checks
  were run, per this slice's filesystem-only instruction.

**Complete for slice 3 domain policy extraction.** No observed pre-existing
business discrepancy was fixed; future application/adapter consolidation and
separate policy/runtime decisions remain outside this slice.

## Slice 4: create admission, canonical pricing and shipping policies

- Completed the authorized admission/pricing fallback, plus shipping distance
  and fee decisions. Framework-free `CheckoutAdmissionPolicy` owns USER create
  admission before the concurrency permit, required fields, COD-only payment,
  item/quantity/duplicate limits, phone/coordinate checks, voucher feature gates,
  selection rules and livestream/flash/voucher incompatibilities. Host maps HTTP
  selections to domain facts without including client money; the stacking
  capability port is evaluated lazily at the original validation point.
- `CheckoutPricingPolicy` owns canonical restaurant/item acceptance, regular vs
  livestream price resolution through `MenuPricePort`, flash identity/quantity
  binding, exact BigDecimal line/subtotal/discount/customer-shipping/total and
  payable-food checks. Create and preview delegate common arithmetic. Streamed
  subtotal resolution retains interleaved lookup/arithmetic exception precedence;
  regular facts are still required even for livestream price overrides. Host
  retains transport decoding, price-fact adapters, flash selection mapping,
  wire-specific errors, reservation rails and immutable persistence snapshots.
- `ShippingPolicy` owns Vietnam finite-coordinate admission, the unchanged
  Haversine calculation, 12,000 base for two km, 4,500 per extra km, surcharge
  `UP` to integer VND, 12,000–50,000 clamp and final 500-VND `HALF_UP` rounding.
  The host facade retains its API (including the unused subtotal parameter) and
  original ValidationException message. No monetary scale normalization was
  introduced; ordinary checkout money remains unrounded exactly as before.
- Domain regressions cover required-field boundaries, null/invalid items,
  duplicate and quantity limits, voucher-mode/count/capability combinations,
  feature incompatibilities and accumulated error order; canonical missing,
  nonpositive/nonfinite facts; livestream lookup precedence, flash binding,
  fractional money, discount limits, payable threshold and legacy shipping
  fallback; every invalid coordinate position, Haversine and both rounding
  stages/base/cap boundaries. Host regressions prove exact accumulated errors
  before remote access and unrounded canonical item/subtotal/total snapshots
  despite an unrelated client price. Existing preview/reservation/quote and
  transaction/compensation regressions remain in the full run.

### Preserved discrepancies observed in slice 4

- Create admission accepts NaN delivery coordinates because it uses only range
  comparisons. Canonical pickup and shipping validation reject nonfinite values;
  the later shipping failure can occur after an Order shell/reservation. Domain
  tests retain that admission behavior rather than silently changing precedence.
- Shipping subtracts double distance before `BigDecimal.valueOf` and ceiling.
  At `2 + 249.0 / 4500` km the nominal 249-VND surcharge drifts upward, ceilings
  to 250 and rounds to a 500-VND surcharge. `Math.nextDown` of that distance rounds
  to zero surcharge. Tests preserve both observed results; no numerical-policy
  repair was made.
- Existing distinct create/preview voucher-mode admission and quote validation
  versus consume error precedence are retained. They must not be unified by a
  future extraction without a separately authorized behavior change.

### Slice 4 final validation and remaining work

- Required `mvn -B -pl :order-service -am clean verify`, escalated for local
  server/Docker access: exit 0, **BUILD SUCCESS**, finished
  2026-10-05T23:19:34+07:00. Log: `/tmp/order-slice4-clean-verify.log`.
- Domain: 30 tests; host: 178 tests; zero failures/errors/skips in both.
  Domain JaCoCo LINE 290/290 (100%), BRANCH 421/422 (99.76%); unchanged 85%
  LINE/BRANCH checks passed. Admission has one unreachable private phone-helper
  null branch because its caller checks non-null before invocation. Pricing and
  shipping production policies have 100% LINE and BRANCH coverage.
- Docker-only skips: **none**. `OrderCreateIdempotencyPostgresConcurrencyTest`,
  `SagaOrderKafkaPostgresIntegrationTest` and
  `SagaOrderCommandPostgresConcurrencyTest` each ran and passed (one test each).
  Executable Spring Boot JAR packaged successfully.
- Focused `mvn -B -pl :order-domain -am clean verify`: exit 0, 30 tests and both
  85% gates passed; `/tmp/order-slice4-domain.log`. The first domain iteration
  exposed the double rounding boundary above; its assertion was corrected to the
  existing behavior and the final full run includes that regression.
- Reviewed filesystem diffs against pre-edit copies and inspected source/whitespace;
  domain has no framework imports. All source edits are under `order/` and
  `order-service/`; no git commands, POM changes, docs/plans edits, coverage-gate
  reductions, production defaults, transaction or schema changes.

**Complete for the authorized admission/pricing fallback and shipping policy.**
Slice 4 remains partial: preview-specific admission, canonical catalog parsing /
line availability, serviceability and ETA response/prep-time policy remain in
`CheckoutPreviewService`; quote owner/input/expiry/used/reprice and consume
policies remain in `CheckoutQuoteService`, with fingerprint/TTL issuance in the
host. Host HTTP clients, transaction/store orchestration, reservation and
compensation/lease recovery remain for slice 5 and adapter consolidation for
slice 6. No new application modules or empty layers were introduced.


## Slice 4 remainder: preview catalog/admission/ETA and quote lifecycle

- Framework-free `CheckoutPreviewPolicy` owns preview's distinct first-error
  admission, item/quantity/duplicate checks, voucher selection/mode and capability
  gating, flash/livestream incompatibilities, canonical restaurant acceptance,
  raw fact coercion, menu line availability and ordered unavailable IDs.
  `CatalogFactsPort` supplies decoded canonical facts; `EtaClient` supplies
  typed min/max/source facts; stacking capability remains lazy. Host DTOs and
  HTTP clients do not enter domain dependencies.
- Canonical checks retain name → latitude → longitude → gated serviceability →
  prep-time → item parsing order. Missing/unparseable prep still defaults to 30;
  the 1–240 bound remains ETA-gated. Boolean string coercion, malformed numeric
  nulls, last duplicate catalog ID winning and exact BigDecimal scale remain
  unchanged. ETA empty/invalid responses retain their IllegalStateException
  cause and host retryable dependency exception mapping; no fallback is added.
- `CheckoutQuotePolicy` owns required/missing quote, owner/expiry/used/input
  validation, consume admission, price comparison, TTL arithmetic and the
  legacy single-voucher repricing selection. Clock and fingerprint suppliers
  preserve short-circuit evaluation. Validate still reports wrong owner as
  QUOTE_MISMATCH before expiry; consume reports wrong owner/expiry as
  QUOTE_EXPIRED before used. Expiry equality is invalid in both paths.
- Host keeps remote pricing before the write transaction, fingerprint encoding,
  replacement persistence before PRICE_CHANGED details, quote UUID generation,
  pessimistic consume lock, entity mutation and independent quote persistence.
  Existing common pricing/shipping arithmetic and rounding are unchanged.
- New domain tests cover admission boundaries/error precedence and feature
  combinations, the catalog price/name/availability/stock matrix, malformed
  facts, coordinate endpoints/nonfinite/adjacent doubles, canonical check order,
  serviceability combinations and eager fact decoding before rejection,
  prep/ETA boundaries and unchanged client causes;
  quote tests enumerate owner × expiry × used × input outcomes, lazy supplier
  precedence, exact nanosecond expiry, TTL and legacy voucher selection.
  Host regressions additionally assert API exception codes/messages, no remote
  pricing for rejected quotes and no mutation of already-consumed quotes.

### Remainder validation

- Definitive `mvn -B -pl :order-service -am clean verify`, escalated for local
  server/Docker access: exit 0, **BUILD SUCCESS**, finished
  2026-10-06T00:45:54+07:00. Log:
  `/tmp/order-slice4-remainder-definitive-clean-verify.log`.
- Domain: 40 tests; host: 182 tests; entire reactor: 279 tests. Zero
  failures/errors/skips. The three Docker tests
  `OrderCreateIdempotencyPostgresConcurrencyTest`,
  `SagaOrderKafkaPostgresIntegrationTest` and
  `SagaOrderCommandPostgresConcurrencyTest` each ran and passed (one test each).
  The executable Spring Boot JAR packaged successfully.
- Domain JaCoCo LINE 477/477 (100%), BRANCH 666/668 (99.70%). Existing 85%
  LINE/BRANCH gates passed unchanged. Quote policy: 100% LINE/BRANCH.
  Preview policy: LINE 152/152 (100%), BRANCH 217/218 (99.54%); its one
  unreachable branch is the trailing non-null legacy voucher check after
  selected IDs/mode checks (a valid legacy voucher already makes IDs nonempty).
  The other unreachable branch is the previously documented create phone null
  helper branch. No reachable policy branch remains uncovered.
- Initial focused preview/quote host run: 22 tests, zero failures/errors/skips,
  `/tmp/order-slice4-remainder-focused.log`. Initial domain verification: 39
  tests, zero failures/errors/skips and unchanged gates passed,
  `/tmp/order-slice4-remainder-domain.log`. Two earlier complete clean runs
  also passed; the definitive run includes the final canonical-port and eager
  serviceability-decoding regressions.
- Reviewed host filesystem diffs against pre-edit copies and inspected final
  source/test whitespace. No framework imports in domain production code.
  Edits stay under `order/` and `order-service/`; no git commands, POM changes,
  docs/plans edits, Kafka consumer configuration, rollout defaults, transaction
  boundaries, schema, monetary arithmetic or rounding changes.

**Slice 4 domain extraction is complete.** Remaining HTTP/transaction/store,
create/reservation/idempotency/compensation orchestration and adapter/boot
consolidation stay in slices 5–6; no empty application modules are introduced.

## Slice 5: create, quote, idempotency and reservation application orchestration

- Added framework-free `order/application-api` and `order/application` modules
  to the root reactor and host dependency graph. Both inherit the existing
  build-parent check and explicitly keep 85% LINE and BRANCH minima. Ports use
  request-scoped opaque handles without importing host entities, Spring, JPA,
  HTTP DTOs or Kafka contracts. Host adapters retain those concrete types.
- `CreateOrderWorkflow` coordinates admission, scoped leased acquisition,
  completed replay, remote/read preflight, host write transaction and owned
  incomplete-lease release. `PrepareOrderWorkflow` preserves selection/quote/
  canonical-fact admission order; `CreateWriteWorkflow` coordinates locked final
  receipt claim/replay before zero-valued shell flush and reservation work.
  The customer check before the host concurrency permit remains in place.
- `QuoteWorkflow` owns preview-before-independent-persistence issuance,
  unlocked validation/repricing, replacement persistence before PRICE_CHANGED,
  and locked consume admission before mutation. Host retains quote UUID/clock,
  fingerprints, transaction annotations/template, repository locks and error
  translation. Existing domain quote policy keeps owner/expiry/input/replay
  precedence; no quote DB lock spans remote pricing.
- `ReservationWorkflow` journals IDs before ambiguous inventory/flash calls,
  coordinates canonical pricing and voucher reservation, local snapshots,
  inventory commit, quote consumption, receipt completion and created outbox.
  `VoucherWorkflow` chooses the existing auto/manual/legacy rails through
  domain `CheckoutReservationPolicy`. Failure releases attempted voucher,
  promotion, flash and inventory IDs in precisely that order, retaining the
  original exception and ordered suppressed release failures. TTL remains the
  final remote recovery fence. Host retains all HTTP calls, principal/legacy
  payload selection, persistence snapshots, transactions and outbox enqueue.
- `IdempotencyWorkflow` coordinates atomic insert, re-read, expired-lease CAS,
  completed replay, fingerprint checks and final pessimistic claim, including
  the legacy claim overload. `IdempotencyLeasePolicy` owns existing 5-second to
  5-minute bounds, token equality, strictly-after expiry and fingerprint/version
  binding (including legacy comparison direction/null behavior). H2/PostgreSQL SQL,
  DB time/CAS predicates, unique constraint, transaction boundaries, fingerprint
  version and ownership-fenced release remain unchanged host adapters.
- Application tests cover all optional reservation combinations, failures at
  every orchestration stage, identical attempted/released IDs and release error
  suppression, replay short circuits, final-lock ordering, lease admission and
  recovery, quote issuance/repricing/consume order and every voucher rail.
  Real proxied H2/JPA create tests prove snapshot/receipt/outbox atomicity,
  completed replay skipping remote facts, preflight release/retry, ambiguous
  inventory release and rollback after receipt completion. Host regressions
  prove ambiguous legacy voucher, promotion and flash release identity; flash
  release failure preserves the original cause. PostgreSQL tests retain the
  atomic insert race and add application live-owner and expired-lease races,
  old-owner final-claim/release fencing and subsequent recovery.

### Existing recovery boundaries retained

- The real Spring transaction fixture proves a failure after final receipt
  completion rolls back local writes and releases the preflight lease: acquire
  returns a detached handle, distinct from the final locked/managed receipt.
  Application tests additionally preserve the original conditional release
  rule for adapters returning the same handle: an already-completed in-memory
  handle skips release. This is adapter-handle behavior, not a new production
  business policy or a demonstrated production defect.
- Reservation compensation catches failures inside the write operation. A
  failure raised only by the transaction commit after that operation returns
  does not enter reservation compensation. No after-completion hook is added;
  existing remote recovery semantics are retained. Application tests preserve
  commit-failure lease handling. Packaged remote crash/commit-failure recovery
  remains for slice 6; no stronger runtime guarantee is claimed here.
- Previously documented business/runtime discrepancies remain unchanged,
  including inactive RetryableTopic/owner-DLT behavior. No Kafka consumer
  configuration, schema, topics, defaults or business policies were changed.

### Slice 6 plan

1. Extract outbox lease/retry orchestration behind application transaction/store/
   publish ports, preserving token fencing, publish-outside-transaction,
   at-least-once duplicates, retry bounds and DEAD behavior.
2. Relocate host HTTP/Kafka/JPA adapters and composition into infrastructure,
   and entrypoint/config into boot; finish remaining lifecycle/read/cancellation
   application orchestration during consolidation. Preserve artifact/DNS names,
   adapter exception precedence, transaction/lock ordering and existing flags.
3. Retire `order-service` only after packaged HTTP/Kafka/PostgreSQL runtime and
   recovery proof plus reactor/inventory/packaging updates. Decide the existing
   business/runtime discrepancies separately; no consumer-config repair is
   authorized by these equivalence slices.

### Slice 5 final validation

- Required `mvn -B -pl :order-service -am clean verify`, with local server and
  Docker access: exit 0, **BUILD SUCCESS**, finished
  2026-10-06T18:24:58+07:00. Log:
  `/tmp/order-slice5-final-clean-verify.log`.
- Entire reactor: **311 tests**, zero failures/errors/skips. Domain: 43 tests;
  application API: 1; application: 13; host: 197. The executable Spring Boot
  JAR packaged successfully. Host canonical pricing/compensation: 26 tests;
  real create transaction integration: 4 tests, all passed.
- Docker-only skips: **none**. PostgreSQL idempotency concurrency: 3 tests,
  including both application races; Kafka/PostgreSQL Saga integration: 1;
  PostgreSQL Saga concurrency: 1. All ran and passed with real containers.
- Domain JaCoCo LINE 494/494 (100%), BRANCH 708/710 (99.72%). Application
  LINE 106/106 and BRANCH 75/75 (100% each); application API LINE 1/1 (100%),
  with no executable branches. All unchanged 85% LINE/BRANCH gates passed.
  The two existing unreachable domain branches are documented under slice 4;
  the new lease/reservation policies and application workflows have no missed
  lines or branches.
- Focused transaction verification:
  `mvn -B -pl :order-service -am verify -Dtest=OrderCreateTransactionIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false`: exit 0, 4 tests, zero failures/errors/
  skips. `/tmp/order-slice5-transaction-focused.log`.
- An earlier full run (`/tmp/order-slice5-definitive-clean-verify.log`) failed
  only the new fixture's incorrect assumption that acquire and final claim
  share the same managed receipt. Corrected the assertion to the actual detached
  acquire handle, added successful same-key retry after rollback, and reran
  focused plus full clean verification. No production recovery change was made
  to satisfy that assertion.
- Reviewed filesystem diffs against pre-extraction host copies and inspected
  source/whitespace and framework imports. Core production modules have no
  Spring/JPA/host imports. Source edits stay under `order/`, `order-service/`
  and root `pom.xml`; no git commands, docs/plans edits, Kafka consumer
  configuration edits or reductions to coverage gates.

**Slice 5 is complete for the authorized equivalence extraction.** Existing
commit-only compensation limits and previously documented discrepancies are
preserved. Adapter/boot consolidation, outbox application orchestration and
packaged runtime/recovery proof remain for slice 6.
