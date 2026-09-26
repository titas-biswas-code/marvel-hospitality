/**
 * How every service consumes Kafka (ADR-0008): {@code ErrorHandlingDeserializer} around plain strings, JSON turned
 * into the listener's record type by a message converter, manual acknowledgement after the listener returns,
 * blocking exponential retries for technical failures and a dead-letter topic {@code <topic>.DLT} for everything
 * that cannot be processed. Idempotency is not here: it is the inbox starter's job, inside the listener's transaction.
 */
@NullMarked
package com.marvel.hospitality.platform.kafka;

import org.jspecify.annotations.NullMarked;
