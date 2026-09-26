/**
 * The transactional inbox mechanism shared by every service that consumes Kafka messages (ADR-0006,
 * docs/contracts/outbox-and-inbox.md): {@link com.marvel.hospitality.platform.inbox.ProcessedMessageInbox} records a
 * {@code processed_message} row in the caller's own transaction, so a consumer's idempotency check and its business
 * effect commit or roll back together.
 */
@NullMarked
package com.marvel.hospitality.platform.inbox;

import org.jspecify.annotations.NullMarked;
