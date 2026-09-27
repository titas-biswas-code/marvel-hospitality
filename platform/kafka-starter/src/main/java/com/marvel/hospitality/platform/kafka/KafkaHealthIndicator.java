package com.marvel.hospitality.platform.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * {@code kafka} health: UP when the broker answers a {@code describeCluster} within the timeout (ADR-0013 puts it in
 * the readiness group next to the database). Boot 4 has no Kafka health indicator of its own.
 *
 * <p>One {@link Admin} client is created on the first check and reused; an idle admin client holds no connection,
 * so a context that is never asked for its health (most test contexts) never talks to a broker.
 */
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private final Map<String, Object> adminConfig;
    private final Duration timeout;
    private @Nullable Admin admin;

    public KafkaHealthIndicator(Map<String, Object> adminConfig, Duration timeout) {
        super("Kafka health check failed");
        this.adminConfig = Map.copyOf(adminConfig);
        this.timeout = timeout;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        DescribeClusterResult cluster = admin().describeCluster(
                new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()));
        String clusterId = cluster.clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        int nodes = cluster.nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS).size();
        builder.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes);
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = Admin.create(adminConfig);
        }
        return admin;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(1));
        }
    }
}
