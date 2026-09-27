# ADR traceability

For every ADR: each decision, where it is implemented, and the test that proves it. Paths are shortened to the
class or file name. `RRS` = room-reservation-service, `BTPS` = bank-transfer-payment-service, `NS` =
notification-service, `CCPS` = credit-card-payment-service; `platform/<x>` = the `platform/<x>-starter` build.
Anything an ADR decides but the code does not do is listed at the end as **future**, and marked so in the ADR.

Checked on 2026-09-27 (PR-11). Where an ADR's wording had drifted from the code, the ADR text was corrected (listed
at the end); no code was changed for this document.

## ADR-0001 Monorepo, independent services, platform starters

| Decision | Implemented in | Proven by |
|---|---|---|
| One repo; each service a standalone Gradle build with its own wrapper and Dockerfile; no root build | `*/settings.gradle`, `*/build.gradle`, `*/Dockerfile`; no build file at the root | `make build-all`; CI `build` job builds each project on its own |
| Cross-cutting mechanism once, as starters `marvel-*-spring-boot-starter` (security, problem, outbox, inbox, kafka, observability), consumed from source | `platform/settings.gradle`; `includeBuild('../platform')` in each service's `settings.gradle`; explicit versions in each `build.gradle` | Starter tests (`MarvelSecurityAutoConfigurationTest`, `MarvelKafkaAutoConfigurationTest`, …); per-service `SecurityWiringTest`, `ConsumerWiringTest` |
| Docker builds use the repo root as context | `build.context: ..` in `infra/docker-compose.yml` | `make up-apps` |
| Services never import each other; agreements live in `docs/contracts` | Only generated-client code crosses (CCPS spec copy in RRS) | `ArchitectureRulesTest` (RRS, NS) |
| Contract drift mitigated by fixtures, spec comparison and the e2e smoke test | `*/src/test/resources/contracts/*.v1.json`; `make check-contracts`; `infra/e2e/smoke.sh` | `PaymentReceivedPayloadMatchesEventsContract`, `RefundCompletedPayloadMatchesEventsContract` (BTPS), `ReservationStatusChangedMessageMatchesEventsContract` (NS), `ReservationStatusChangedPayloadTest`, `RefundRequestedPayloadTest` (RRS); CI `contracts` and `e2e` jobs |

## ADR-0002 Property as a first-class concept

| Decision | Implemented in | Proven by |
|---|---|---|
| `property` table (id, name, timezone, bank account); every property-scoped row has `property_id` | RRS `V1__schema.sql`, `R__seed_reference_data.sql` (AMS01, RTM01) | `ReservationPersistenceTest` |
| `propertyId` in the path; authorization `path ∈ token.properties` or `*`; `403 FORBIDDEN_PROPERTY` | `ReservationController`, `UnmatchedPaymentController` (`@propertyAccess.allowed(#propertyId)`); platform/security `PropertyAccess`, `SecurityProblemHandler` | `PropertyAccessTest`; `MarvelSecurityAutoConfigurationTest.propertyNotInClaimIsForbiddenProperty`; `ReservationControllerTest.rejectsCreateForPropertyNotInClaim` |
| `propertyId` in every event value and header (not the brief's bank topic); reservation ids globally unique | Outbox `property_id` column → `propertyId` header (`infra/debezium/*.json`); `reservation_id` `UNIQUE`; `ReservationIdGenerator` + retry | `ReservationOutboxCdcTest`; `CreateReservationUseCaseTest.retriesWithNewIdOnCollision`, `failsAfterFiveCollisions` |
| No "tenant" in code | — | `grep -ri tenant` finds nothing outside build output |

## ADR-0003 PostgreSQL features

| Decision | Implemented in | Proven by |
|---|---|---|
| Postgres 17, database per service with its own role, Flyway + repeatable seed | `infra/postgres/init/01-databases.sh`; `*/src/main/resources/db/migration/` | `FlywayMigrationTest` (BTPS, NS); every RRS persistence test migrates a fresh container |
| Exclusion constraint with `btree_gist` | RRS `V1__schema.sql` `reservation_no_overlap` | ADR-0005 below |
| Generated stay range and nights; 30-night `CHECK` as second line of defence | `stay`, `nights` `GENERATED ALWAYS … STORED`; `reservation_dates_chk` | `ReservationPersistenceTest`; the domain rule in `ReservationTest.rejectsStayLongerThanThirtyNights` (the `CHECK` itself is never reached by the application) |
| Inbox in one round trip (`INSERT … ON CONFLICT DO NOTHING RETURNING`) | platform/inbox `ProcessedMessageInbox` | `ProcessedMessageInboxTest.duplicateIsReportedAndNotInsertedTwice` |
| `FOR UPDATE SKIP LOCKED` for the multi-instance job | `ReservationJpaRepository.claimIfDue` | `AutoCancelIntegrationTest.twoConcurrentJobRunsDoNotCancelTheSameRowTwice` |
| `jsonb` for opaque payloads; status columns `varchar` + `CHECK`, never `ENUM`; `@Version` | `outbox_event.payload`, `bank_transaction.raw`; every status column; `ReservationEntity` | `ReservationPersistenceTest.updateRejectsAStaleSnapshot` |
| Replication-slot lag exported as a metric | platform/outbox `ReplicationSlotLagMonitor` (`debezium.slot.lag.bytes{slot}`) | `ReplicationSlotLagMonitorTest` |
| Real Postgres in tests, never H2 | Testcontainers `@ServiceConnection` in every service | no H2 dependency anywhere |

## ADR-0004 State machine, statuses as text, reference data

| Decision | Implemented in | Proven by |
|---|---|---|
| Enums `ReservationStatus`, `PaymentMode`, `RoomSegment`, `PaymentMatchOutcome`, `RefundReason`, `CancellationReason`; transitions in the aggregate; illegal ones throw | RRS `domain/`; `Reservation` (`confirm`, `cancel`, `recordPartialPayment`, `confirmPayment`) | `ReservationTest` (every transition, `cancelledReservationCannotBeConfirmed`, `failedTransitionLeavesStateAndEventsUntouched`) |
| `GET /reference-data` from `Enum.values()`, public | `ReferenceDataController`; `marvel.security.additional-public-paths` | `ReservationControllerTest.referenceDataListsAllEnumValues`; `SecurityWiringTest.referenceDataIsPublicInThisService` |
| Payment modes as `PaymentModeHandler` strategies in a map | `CashPaymentModeHandler`, `BankTransferPaymentModeHandler`, `CreditCardPaymentModeHandler`; `CreateReservationUseCase` | `CreateReservationUseCaseTest.cashAndBankTransferNeverCallThePaymentService` |

## ADR-0005 Overbooking prevention with an exclusion constraint

| Decision | Implemented in | Proven by |
|---|---|---|
| `EXCLUDE USING gist (property_id =, room_number =, stay &&) WHERE status <> 'CANCELLED'`, half-open range | RRS `V1__schema.sql` | `ReservationPersistenceTest.overlappingStayOnSameRoomIsRejectedByExclusionConstraint`, `backToBackStaysOnSameRoomAreAllowed`, `cancelledReservationDoesNotBlockTheRoom`, `concurrentInsertsForSameRoomOnlyOneSucceeds` (two real transactions, `23P01`) |
| `23P01` on `reservation_no_overlap`, by name, → `409 ROOM_UNAVAILABLE`; nothing blanket-mapped | `JpaReservationRepositoryAdapter.add`; platform/problem `ConstraintNames`; `ReservationProblemAdvice` | `ReservationControllerTest.returns409ForRoomUnavailable`; `ConstraintNamesTest` |
| No `FOR UPDATE` pre-check (the read is lock-free and advisory only) | `ReservationJpaRepository.existsOverlapping` | `ReservationPersistenceTest.isBookedMirrorsTheExclusionConstraint` |

## ADR-0006 Sagas, outbox, inbox, compensation

| Decision | Implemented in | Proven by |
|---|---|---|
| Step 1: reservation + outbox status event in one transaction | `CreateReservationUseCase` | `ReservationControllerTest.createsBankTransferReservationWithDeadlineAndInstructions` |
| Step 2: bank transaction + outbox `PaymentReceived` | `IngestBankTransactionUseCase` | `BankTransactionControllerTest`; `IngestBankTransactionConcurrencyTest` |
| Step 3: inbox(paymentId) + payment + status + `RefundRequested` | `ApplyBankPaymentUseCase`, `RefundPolicy` | `BankTransferPaymentConsumerIntegrationTest.fullPaymentConfirmsReservationAndEmitsStatusEvent`, `overpaymentRequestsRefundOfSurplusInSameTransaction`, `paymentAfterCancellationRequestsFullRefund` |
| Step 4: timeout = auto-cancel | `CancelOverdueReservationUseCase`, `AutoCancelJob` | ADR-0010 below |
| Step 5: inbox(refundId) + refund instruction + `RefundCompleted` | BTPS `ExecuteRefundUseCase` | `RefundRequestedConsumerIntegrationTest.refundRequestedCreatesInstructionToOriginalDebtorAndEmitsCompleted`, `duplicateRefundRequestedIsIgnored` |
| Step 6: inbox(refundId) + refund marked completed | RRS `CompleteRefundUseCase` | `RefundCompletedConsumerIntegrationTest.refundCompletedMarksRefundCompleted`, `refundFailedMarksRefundFailedAndCountsMetric` |
| Outbox row only inside the caller's transaction | platform/outbox `OutboxEventWriter` | `OutboxEventWriterTest.joinsCallerTransactionAndRollsBackWithIt`, `refusesToWriteOutsideATransaction` |
| Inbox row in the effect's transaction; duplicates acknowledged and skipped | platform/inbox `ProcessedMessageInbox` | `ProcessedMessageInboxTest.rolledBackEffectAlsoRollsBackInboxRow`; `BankTransferPaymentConsumerIntegrationTest.duplicatePaymentIdIsAcknowledgedAndAppliedOnce` |
| Order per aggregate by key; payments commute | Outbox `aggregate_id` → key; sum-based matching | `BankTransferPaymentConsumerIntegrationTest.twoPartialPaymentsConfirmOnSecond` (both orders) |
| Never `KafkaTemplate` for domain events; choreography, no orchestrator | Only the DLT publisher uses `KafkaTemplate` | `ArchitectureRulesTest.noKafkaTemplateInApplicationCode` |
| One trace per saga through the outbox | `OutboxEventWriter` stores `traceparent`; header mapping in `infra/debezium/*.json` | `ReservationOutboxCdcTest.debeziumCopiesTraceparentIntoHeader`; `KafkaTracePropagationTest.listenerContinuesTraceFromTraceparentHeader` |
| The whole saga end to end | compose stack | `infra/e2e/smoke.sh` (CI `e2e` job) |

## ADR-0007 Debezium Outbox Event Router

| Decision | Implemented in | Proven by |
|---|---|---|
| One Postgres connector (`pgoutput`) + Outbox Event Router per outbox database; route by `topic`, key `aggregate_id`, metadata as headers | `infra/debezium/reservation-outbox.json`, `payment-outbox.json` | `ReservationOutboxCdcTest.outboxRowIsPublishedToConfiguredTopicWithKeyAndHeaders`, `PaymentOutboxCdcTest` (real Connect container, the committed connector JSON) |
| Value = stored `jsonb` text (`table.expand.json.payload=false`, `StringConverter`) | same files | `PaymentOutboxCdcTest` (`120.00` stays `120.00`) |
| Registered idempotently by `connect-init` | `infra/connect-init/register-connectors.sh` (`PUT …/config`, waits for `RUNNING`) | `make up-apps` (runs it every time) |
| Slot retention mitigations: lag metric, `max_slot_wal_keep_size`, runbook | `ReplicationSlotLagMonitor`; `infra/docker-compose.yml`; `infra/README.md` runbook; Grafana panels | `ReplicationSlotLagMonitorTest`. **Alert rule: future** |
| Connector down loses nothing | — | [docs/cdc-durability-demo.md](../cdc-durability-demo.md) (manual) |
| Outbox never read by the application; purged after 7 days | platform/outbox `OutboxPurgeJob`, `MarvelOutboxProperties` | `OutboxPurgeJobTest.purgesOnlyRowsOlderThanRetention` |

## ADR-0008 Kafka consumption

| Decision | Implemented in | Proven by |
|---|---|---|
| No auto-commit, `MANUAL_IMMEDIATE`, ack after the use case's commit, `commitRecovered` | platform/kafka `MarvelKafkaAutoConfiguration` | `KafkaErrorHandlingTest.listenerContainerAcknowledgesManuallyAndConsumerNeverAutoCommits`, `deadLetteredRecordIsCommittedAndCounted` |
| `ErrorHandlingDeserializer`; 5 blocking attempts, 1 s ×2 max 30 s; listed exceptions not retried | same; `MarvelKafkaProperties.Retry` | `KafkaErrorHandlingTest.retryableFailureIsRetriedWithBackoffThenDeadLettered`, `nonRetryableFailureBypassesBackoff`; `BankTransferPaymentConsumerIntegrationTest.exhaustedRetriesSendToDlt`, `malformedJsonGoesToDltWithoutRetries`, `invalidPaymentGoesToDltWithoutRetries` |
| Business outcomes are not errors | `ApplyBankPaymentUseCase` (unmatched payments stored, acknowledged) | `BankTransferPaymentConsumerIntegrationTest.unmatchedFormatIsStoredWithoutReservation`, `unknownReservationIsStored` |
| `<topic>.DLT`, same partition, headers kept; `kafka.dlt.messages{topic}`; manual replay | platform/kafka `DeadLetterPublisher`; `scripts/replay-dlt.sh` (`make replay-dlt`) | `KafkaErrorHandlingTest.deadLetteredRecordIsCommittedAndCounted`; `BankTransferPaymentConsumerIntegrationTest.nextMessageOnSamePartitionIsProcessedAfterPoisonMessageIsDeadLettered` |
| Inbox consumer name fixed per listener, never the group; group = service name; concurrency 3; topics created by infra | `ProcessedMessage*Inbox.CONSUMER`; `application.yml`; `infra/kafka/create-topics.sh` | `ConsumerWiringTest` (each consuming service); `ProcessedMessageInboxTest.sameMessageIdForAnotherConsumerIsIndependent` |
| KIP-848 group protocol | — | **future** (the ADR's own "Future" section) |

## ADR-0009 Payment matching

| Decision | Implemented in | Proven by |
|---|---|---|
| Store every payment first, then classify: `UNMATCHED_FORMAT`, `UNMATCHED_UNKNOWN_RESERVATION`, `UNMATCHED_NOT_PENDING` (+ full refund), `MATCHED_PARTIAL`, `MATCHED_FULL`, `OVERPAID` (+ surplus refund) | `PaymentMatcher`, `ApplyBankPaymentUseCase`, `RefundPolicy` | `PaymentMatcherTest` (every outcome); `BankTransferPaymentConsumerIntegrationTest` (one test per outcome) |
| Match on the reservation id only; exact scale-2 amounts; sum-based so order does not matter | `PaymentMatcher.parse`, `Money` | `PaymentMatcherTest.rejectsLowercaseReservationId`; `twoPartialPaymentsConfirmOnSecond` |
| Concurrent payments serialised by a row lock; `@Version` as backstop | `ReservationJpaRepository.findByReservationId` (`PESSIMISTIC_WRITE`) | `BankTransferPaymentConsumerIntegrationTest.concurrentPaymentsForSameReservationSerialise` |
| `amountReceived` = sum of matched payments, `UNMATCHED_NOT_PENDING` never counts | `JpaReceivedPaymentRepositoryAdapter.sumMatched` | `ReceivedPaymentPersistenceTest`; `smoke.sh` step 10 |
| Unmatched payments not refunded; visible for reconciliation | `UnmatchedPaymentController` | `UnmatchedPaymentControllerTest` |
| Tolerance for bank fees, fuzzy matching for humans | — | **future** / rejected for automation |

## ADR-0010 Auto-cancel scheduler

| Decision | Implemented in | Proven by |
|---|---|---|
| Deadline = local midnight two days before arrival, stored at creation; past deadline → `422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT` | `PaymentDeadlinePolicy`, `BankTransferPaymentModeHandler` | `PaymentDeadlinePolicyTest`; `ReservationControllerTest.returns422WhenBankTransferLeadTimeTooShort` |
| Unlocked keyset pages, per-row `FOR UPDATE SKIP LOCKED` claim in `REQUIRES_NEW`, cancel + outbox in that transaction | `AutoCancelJob`, `CancelOverdueReservationUseCase`, `ReservationJpaRepository.findDueFirstPage`/`findDueAfter`/`claimIfDue` | `AutoCancelIntegrationTest.twoConcurrentJobRunsDoNotCancelTheSameRowTwice`, `oneFailingRowDoesNotRollBackTheOthers`, `restartSafe_rowsDueWhileDownAreCancelledOnNextRun`, `emitsStatusChangedWithDeadlineMissedReason`, `doesNotCancelBeforeDeadline`, `cancelsExactlyAtDeadline` |
| Injected `Clock`, no sleeping | `MutableClock` (tests) | `AutoCancelIntegrationTest` |
| Late payment → `UNMATCHED_NOT_PENDING` + refund | `PaymentMatcher` | `AutoCancelIntegrationTest.paymentArrivingAfterCancellationIsStoredAsNotPending`; `smoke.sh` step 10 |
| Metrics `reservation.autocancel.cancelled{propertyId}`, `reservation.autocancel.overdue` | `AutoCancelJob` | `AutoCancelJobTest.overdueGaugeShowsRowsStillDueAfterTheRun` |

## ADR-0011 Credit-card integration

| Decision | Implemented in | Proven by |
|---|---|---|
| Rules and lock-free availability read first; call without any DB transaction; then a short insert transaction | `CreateReservationUseCase.verifyBeforeStoring`, `CreditCardPaymentVerification` | `NoTransactionDuringPaymentCallTest.noDatabaseTransactionIsActiveDuringPaymentCall`; `CreditCardReservationIntegrationTest.returns409WithoutCallingPaymentServiceWhenRoomAlreadyBooked` |
| `CONFIRMED` → 201; `REJECTED`/404 → `422 PAYMENT_REJECTED`; timeout/5xx/open circuit → `503` + `Retry-After`; nothing stored on failure | `CreditCardPaymentClientAdapter`, `ReservationProblemAdvice` | `CreditCardReservationIntegrationTest` (`returns422AndPersistsNothingWhenPaymentRejected`, `…NotFound`, `returns503OnReadTimeout`, `returns503AfterRetriesExhaustedOn500`) |
| Client generated from the corrected spec; two identical spec copies | RRS `build.gradle` `openApiGenerate`; `make check-contracts`; [defects](../credit-card-spec-defects.md) | CI `contracts` job |
| Timeouts 1 s / 2 s in the HTTP client; retry 3 attempts on 5xx/I/O; circuit breaker 20 / 50 % / 10 s / 3 | `CreditCardClientConfiguration`; `resilience4j.*.creditCardPayment` in RRS `application.yml` | `retriesOnceOn500ThenSucceeds`, `doesNotRetryOn4xx`, `circuitOpensAfterFailureThresholdAndFailsFast` |
| Room taken after a confirmed payment → 409 + WARN "needs manual reconciliation"; one card payment backs one reservation (`409 PAYMENT_REFERENCE_ALREADY_USED`) | `CreateReservationUseCase`; V2 index `reservation_credit_card_payment_reference_uq` | `returns409WhenRoomTakenAfterPaymentConfirmed`; `ReservationPersistenceTest.creditCardPaymentReferenceCanBackOnlyOneReservationAcrossProperties` |
| Stub without security, deterministic by reference prefix | CCPS `PaymentStatusController` | `PaymentStatusControllerTest` |
| Automatic compensation of a paid card without reservation | — | **future** (asynchronous card saga, ADR-0006) |

## ADR-0012 Security

| Decision | Implemented in | Proven by |
|---|---|---|
| Keycloak realm `marvel` from an idempotent bootstrap script and a committed export | `infra/keycloak/bootstrap.sh`, `export.sh`, `realm/marvel-realm.json` | `KeycloakRealmSmokeTest.aliceTokenFromCommittedRealmIsAcceptedWithHerRolesAndProperties`, `bankSimulatorServiceAccountCarriesBankRolesAndAllProperties` |
| Every service validates the JWT; realm roles → authorities; property check; 401/403 problem details; `/whoami` | platform/security `MarvelSecurityAutoConfiguration`, `KeycloakRealmRoleConverter`, `PropertyAccess`, `SecurityProblemHandler`, `WhoamiController` | `MarvelSecurityAutoConfigurationTest`; `KeycloakRealmRoleConverterTest`; `SecurityWiringTest` (each service) |
| Issuer / JWKS split | `issuer-uri` + `jwk-set-uri` in each `application.yml` (`local` profile) | `KeycloakRealmSmokeTest`; `make up-apps` + `smoke.sh` |
| Service accounts carry `properties: ["*"]`; the simulator uses client credentials | `bootstrap.sh`; `bank-transfer-simulator/scripts/token.sh` | `KeycloakRealmSmokeTest.bankSimulatorServiceAccountCarriesBankRolesAndAllProperties` |
| No gateway; card stub unauthenticated; secrets only as local defaults | `infra/docker-compose.yml`; `infra/.env.example` | — |
| Service-to-service REST with client credentials (reservation → payment), gateway with token relay | clients reserved in `bootstrap.sh` | **future** |

## ADR-0013 Observability

| Decision | Implemented in | Proven by |
|---|---|---|
| Micrometer tracing + metrics over OTLP to one `otel-lgtm` | `spring-boot-starter-opentelemetry`; platform/observability; `infra/docker-compose.yml` | `MarvelObservabilityAutoConfigurationTest` |
| `traceparent` through HTTP, Kafka and the outbox | Boot defaults (HTTP); `setObservationEnabled(true)` in platform/kafka; `OutboxEventWriter` | `KafkaTracePropagationTest`; `ReservationOutboxCdcTest.debeziumCopiesTraceparentIntoHeader` |
| JSON logs (ECS) with MDC `traceId`, `spanId`, `propertyId`, `reservationId`, `paymentId`, `refundId`; shipped to Loki | `LoggingContext`; `OpenTelemetryLogbackInstaller` | `LoggingContextTest`; `MarvelObservabilityAutoConfigurationTest.exportsLogRecordsWithMdcIdsAsAttributes` |
| Metrics `reservation.created`, `reservation.autocancel.cancelled`, `payment.matched`, `refund.requested`, `kafka.dlt.messages`, `debezium.slot.lag.bytes`, tag `service` | `ReservationController`, `AutoCancelJob`, `BankTransferPaymentUpdateListener`, `DeadLetterPublisher`, `ReplicationSlotLagMonitor` | `ReservationControllerTest`, `AutoCancelJobTest`, `BankTransferPaymentConsumerIntegrationTest`, `KafkaErrorHandlingTest`, `ReplicationSlotLagMonitorTest`, `MarvelObservabilityAutoConfigurationTest.tagsEveryMeterWithServiceName` |
| Readiness includes DB and Kafka | `management.endpoint.health.group.readiness`; Kafka indicator in platform/kafka | `ReadinessIntegrationTest` (each service) |
| Grafana dashboards (BONUS) | `infra/grafana/dashboards/*.json` | — |

## ADR-0014 bank-transfer-payment-service and the simulator

| Decision | Implemented in | Proven by |
|---|---|---|
| `POST /bank-transactions`, idempotent on `bankTransactionRef`; ledger with raw `jsonb` and own `paymentId` | `BankTransactionController`, `IngestBankTransactionUseCase`, BTPS `V1__schema.sql` | `BankTransactionControllerTest.duplicateBankTransactionRefReturns200SameIdAndNoNewOutboxRow`; `IngestBankTransactionConcurrencyTest` |
| Publishes `PaymentReceived` with the brief's field names; knows nothing about reservations | `PaymentOutboxWriter`, `PaymentReceivedPayload` | `PaymentReceivedPayloadMatchesEventsContract`; `PaymentOutboxCdcTest` |
| Owns refunds: inbox on `refundId`, instruction to the debtor account from its ledger, stub rail (`FAIL…` rejected), `RefundCompleted` | `RefundRequestedListener`, `ExecuteRefundUseCase`, `StubRefundExecutor` | `RefundRequestedConsumerIntegrationTest.failingAccountEmitsFailedCompletion`; `RefundCompletedCdcTest` |
| Simulator = scripts + Postman, no build | `bank-transfer-simulator/scripts/*.sh`; `docs/postman/` | `smoke.sh` uses the scripts |

## ADR-0015 Testing strategy

| Layer | Where |
|---|---|
| Domain, no Spring | `ReservationTest`, `PaymentMatcherTest`, `PaymentDeadlinePolicyTest`, `MoneyTest`, `BankTransactionTest`, `RefundInstructionTest`, `NotificationRendererTest` |
| Application with Mockito on ports | `CreateReservationUseCaseTest` |
| Persistence on Testcontainers Postgres | `ReservationPersistenceTest`, `ReceivedPaymentPersistenceTest`, `RefundPersistenceTest`, `FlywayMigrationTest` |
| REST slices with `jwt()` | `ReservationControllerTest`, `UnmatchedPaymentControllerTest`, `BankTransactionControllerTest`, `RefundControllerTest`, `NotificationControllerTest` |
| Kafka on Testcontainers, one listening context per service | `KafkaListenersIntegrationTest` (abstract, per service) and its subclasses; `ListenerAssignments.awaitFullyAssigned` |
| HTTP client with WireMock | `CreditCardReservationIntegrationTest` |
| Platform starters, then a wiring test per service | `Marvel*AutoConfigurationTest`; `SecurityWiringTest`, `ConsumerWiringTest` |
| Keycloak smoke test | `KeycloakRealmSmokeTest` |
| CDC per outbox service | `ReservationOutboxCdcTest`, `RefundRoundTripCdcTest`, `PaymentOutboxCdcTest`, `RefundCompletedCdcTest` |
| End to end | `infra/e2e/smoke.sh`, CI `e2e` job |
| Rules: no H2, no `Thread.sleep` (Awaitility), `Clock` test double | `MutableClock`; no H2 dependency; no `Thread.sleep` in any test |

## ADR-0016 Money

| Decision | Implemented in | Proven by |
|---|---|---|
| `Money` record: scale 2 with `RoundingMode.UNNECESSARY`, non-negative, EUR only, domain operations only | RRS `domain/Money` | `MoneyTest` |
| Never crosses a boundary; flat `BigDecimal` + `currency` in DTOs, payloads and entities; plain-notation JSON | `ReservationEntity`, `ReservationResponse`, outbox payloads; `spring.jackson.write.write-bigdecimal-as-plain` (RRS, BTPS) | `MoneySerialisationTest`; `ReservationStatusChangedPayloadTest` |
| JavaMoney | — | **future** (multi-currency) |

## Future (decided, not implemented)

| ADR | What | Why not now |
|---|---|---|
| 0003 / 0002 | Row-level security on `property_id` | Awkward with connection pooling; doubles the test surface |
| 0006 / 0011 | Orchestrated saga; asynchronous card payments (closes the "paid card, no reservation" gap) | The brief asks for a synchronous card flow; choreography fits the current flow |
| 0007 | Alert rules on slot lag (and on DLT count, failed refunds, overdue auto-cancel rows) | Metrics and dashboards exist; nothing pages anyone yet |
| 0008 | KIP-848 consumer group protocol | Adopt when rebalance pauses show up in lag |
| 0009 | Tolerance rule for bank fees | Business rule not specified |
| 0012 | Gateway with token relay; service-account tokens on service calls | No service-to-service REST call needs one yet |
| 0016 | JavaMoney | Single currency |

## ADR text corrected on 2026-09-27

The code was right and the ADR text was not; each ADR's status line records the correction.

- **0001**: contract drift is caught by payload tests against committed contract examples, not a "JSON schema check".
- **0003**: the outbox purge is an idempotent `DELETE`, not a `SKIP LOCKED` job.
- **0004**: the payment mutators are `recordPartialPayment` and `confirmPayment`, not `recordPayment`.
- **0007**: the slot-lag *alert* is future (metric and dashboard only).
- **0009**: "never another property's reservation" holds by construction; it was never a runtime assertion.
- **0014**: the refund's debtor account comes from the payment service's own ledger; `refund-requested` does not
  carry it.
- **0015**: the Keycloak smoke test proves real-token acceptance; wrong-property 403 is proven with `jwt()` tests;
  test data comes from small factory methods per test class, not shared builders.
