package com.project.souklab.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Reports payment-provider configuration readiness without exposing credentials. */
@Component("chargily")
@Profile({"prod", "production"})
@ConditionalOnProperty(name = "app.chargily.enabled", havingValue = "true")
public class ChargilyHealthIndicator implements HealthIndicator {
    private final ChargilyProperties properties;

    public ChargilyHealthIndicator(ChargilyProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        boolean configured = properties != null
                && hasText(properties.getApiKey())
                && hasText(properties.getSecretKey())
                && hasText(properties.getBaseUrl())
                && hasText(properties.getWebhookEncryptionKey());
        return configured ? Health.up().build() : Health.down().build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
