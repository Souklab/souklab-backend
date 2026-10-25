package com.project.souklab.config;

import com.project.souklab.analytics.AnalyticsMetric;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Reports TCP reachability of the RabbitMQ STOMP relay endpoint in production. */
@Component("relay")
@Profile({"prod", "production"})
public class RelayHealthIndicator implements HealthIndicator {

    private final RelayProperties properties;
    private final OperationalMetrics metrics;
    private final HealthProperties healthProperties;

    public RelayHealthIndicator(RelayProperties relayProperties, OperationalMetrics metrics, HealthProperties healthProperties) {
        this.properties = relayProperties;
        this.metrics = metrics;
        this.healthProperties = healthProperties;
    }

    @Override
    public Health health() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()),
                    Math.toIntExact(healthProperties.getDependencyTimeout().toMillis()));
            metrics.setDependencyAvailability(AnalyticsMetric.Operational.Dependency.RabbitMq.VALUE, true);
            return Health.up().build();
        } catch (IOException exception) {
            metrics.setDependencyAvailability(AnalyticsMetric.Operational.Dependency.RabbitMq.VALUE, false);
            return Health.down().build();
        }
    }
}
