package com.marvel.hospitality.platform.outbox;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param producer the {@code outbox_event.producer} value this service writes; when unset,
 *        {@link MarvelOutboxAutoConfiguration} falls back to {@code spring.application.name}
 */
@ConfigurationProperties("marvel.outbox")
public record MarvelOutboxProperties(@Nullable String producer) {
}
