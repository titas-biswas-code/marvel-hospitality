package com.marvel.hospitality.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Project rules checked on the source tree, so they cannot erode silently: the domain stays plain Java, "now" only
 * ever comes from the injected {@code Clock}, and no domain event is ever published straight to Kafka (ADR-0006):
 * every state change other services care about goes through the outbox, which {@link
 * com.marvel.hospitality.platform.outbox.OutboxEventWriter} writes inside the caller's own transaction; Debezium is
 * the only publisher. A source scan is enough for rules this simple.
 */
class ArchitectureRulesTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/marvel/hospitality/payment");
    private static final Pattern FRAMEWORK_IMPORT =
            Pattern.compile("^import (static )?(org\\.springframework|jakarta\\.persistence|org\\.hibernate)\\.", Pattern.MULTILINE);
    private static final Pattern CLOCKLESS_NOW =
            Pattern.compile("\\b(Instant|LocalDate|LocalDateTime|LocalTime|OffsetDateTime|ZonedDateTime)\\.now\\(\\s*\\)");
    private static final Pattern KAFKA_TEMPLATE_IMPORT =
            Pattern.compile("^import (static )?.*\\bKafkaTemplate\\b", Pattern.MULTILINE);

    @Test
    void domainHasNoSpringImports() throws IOException {
        assertThat(filesMatching(MAIN_SOURCES.resolve("domain"), FRAMEWORK_IMPORT)).isEmpty();
    }

    @Test
    void noDirectNowCallsOutsideClock() throws IOException {
        assertThat(filesMatching(MAIN_SOURCES, CLOCKLESS_NOW)).isEmpty();
    }

    @Test
    void noKafkaTemplateInApplicationCode() throws IOException {
        // ADR-0006: the application never calls KafkaTemplate for a domain event; the outbox is the only path to
        // Kafka. (KafkaTemplate is allowed only inside platform's DLT error-handling wiring, never here.)
        assertThat(filesMatching(MAIN_SOURCES, KAFKA_TEMPLATE_IMPORT)).isEmpty();
    }

    private static List<Path> filesMatching(Path root, Pattern pattern) throws IOException {
        assertThat(root).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> pattern.matcher(read(file)).find())
                    .toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
