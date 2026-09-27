package com.marvel.hospitality.platform.outbox;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Renders the current span as a {@code traceparent} through Micrometer Tracing's own {@link Propagator}, the same one
 * that writes the header on outgoing HTTP calls, so the stored value has exactly the format consumers read back.
 * The tracer and propagator are looked up when a row is written, not when this bean is created, so the outbox
 * auto-configuration does not depend on the order in which Boot's tracing auto-configuration runs.
 */
class MicrometerTraceparentSource implements TraceparentSource {

    static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    MicrometerTraceparentSource(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public @Nullable String currentTraceparent() {
        Tracer currentTracer = tracer.getIfUnique();
        Propagator currentPropagator = propagator.getIfUnique();
        if (currentTracer == null || currentPropagator == null) {
            return null;
        }
        TraceContext context = currentTracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        currentPropagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
