# ADR-0013 Observability

Status: Accepted · Date: 2026-09-26

## Context
A saga across three services and Kafka is only debuggable with correlated traces, structured logs and a
few well-chosen metrics.

## Decision
- OpenTelemetry via Micrometer Tracing (OTel bridge) and Micrometer metrics, exported over OTLP to a
  single `grafana/otel-lgtm` container (Grafana + Loki + Tempo + Prometheus/Mimir in one image).
- Trace propagation: HTTP (W3C `traceparent`) and Kafka (headers) automatically; outbox rows store
  `traceparent` and Debezium copies it into a header, so a trace spans producer → CDC → consumer.
- Structured JSON logs (Spring Boot structured logging, ECS or logstash format) with MDC
  `traceId, spanId, propertyId, reservationId, paymentId, refundId` set by a small `LoggingContext` helper.
  Logs shipped to Loki via the OTel appender or Promtail on the compose network.
- Metrics that matter (all tagged `service`, `propertyId` where sensible):
  `reservation.created{paymentMode,status}`, `reservation.autocancel.cancelled`,
  `payment.matched{outcome}`, `refund.requested{reason}`, `kafka.dlt.messages{topic}`,
  `debezium.slot.lag.bytes{slot}` (from `pg_replication_slots`), plus Boot's HTTP/Kafka/JDBC defaults.
- Health: liveness/readiness groups; readiness includes DB and Kafka.
- A provisioned Grafana dashboard JSON (`infra/grafana/dashboards/marvel.json`) is BONUS.

## Consequences
- One extra compose service instead of four.
- The `traceparent` column on `outbox_event` is required by the contract.

## Alternatives considered
- Separate Prometheus + Loki + Tempo + Grafana containers: same result, four times the compose surface; rejected.
- Zipkin only: no logs/metrics correlation; rejected.
