# ADR-0006 No distributed transactions: sagas, transactional outbox, inbox idempotency, compensation

Status: Accepted · Date: 2026-09-26

## Context
A bank-transfer booking spans three services and hours to days of wall-clock time. The reviewer wants
to see "an event-driven system including a distributed transaction". The honest engineering answer is
that there is **no** distributed transaction: XA/2PC across microservices and Kafka is unavailable
(Kafka is not an XA resource) and undesirable (coordinator locks, availability coupling).

## Decision
Replace the distributed transaction with a **saga**: a sequence of local ACID transactions connected by
at-least-once messaging, with idempotent consumers and explicit compensating actions.

Local-transaction boundaries (each is one `@Transactional` unit; each writes its outbox in the same tx):
1. reservation-service: create reservation `PENDING_PAYMENT` + outbox `ReservationStatusChanged`.
2. payment-service: ingest bank transaction + outbox `PaymentReceived` (`bank-transfer-payment-update`).
3. reservation-service: inbox(paymentId) + record payment + maybe `CONFIRMED` + outbox status event
   (+ outbox `RefundRequested` on overpayment / cancelled reservation).
4. scheduler in reservation-service: `CANCELLED` + outbox status event (timeout branch of the saga).
5. payment-service: inbox(refundId) + refund instruction + "execute" + outbox `RefundCompleted`.
6. reservation-service: inbox(refundId) + mark refund completed.

Guarantees:
- **Atomicity of state + event**: transactional outbox (contracts/outbox-and-inbox.md), delivered by CDC (ADR-0007).
- **At-least-once delivery + effectively-once processing**: inbox table `processed_message` written in
  the same transaction as the effect; duplicates are acknowledged and skipped.
- **Ordering**: per aggregate via Kafka key (`reservationId` / `paymentId`); business logic is written
  to be order-insensitive where cheap (payments are summed, so partial payments commute).
- **Compensation**: refunds are the compensating action for money that arrived after cancellation or in
  excess. Compensation is itself a saga step with its own outbox/inbox.
- **Timeouts**: the auto-cancel scheduler is the saga's timeout handler (ADR-0010).
- **Observability**: `traceparent` propagates through outbox → headers → consumer so one trace spans the saga.

Choreography (no orchestrator service) because the flow is short and each step has one obvious owner.

## Consequences
- Every consumer must be idempotent; every producer must write outbox rows, never `KafkaTemplate`.
- Eventual consistency is visible to callers (a reservation is `PENDING_PAYMENT` for a while); the API
  reflects that honestly instead of pretending synchrony.
- Failure handling is explicit per step (ADR-0008) rather than global rollback.

## Alternatives considered
- XA/2PC: unavailable for Kafka; rejected.
- Orchestration (a `ReservationSaga` state row driving commands): better for long flows with many
  branches; here it would add a service and a command topic set for little gain. Documented as the
  upgrade path if the credit-card flow becomes asynchronous.
- Dual write (save then publish): loses events on crash between the two writes; rejected.
