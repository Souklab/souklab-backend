package com.project.souklab.service.subscription;

import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.model.BillingPeriod;
import com.project.souklab.model.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionPlanRulesTest {
    private SubscriptionPlanRules rules;

    @BeforeEach
    void setUp() {
        SubscriptionProperties properties = new SubscriptionProperties();
        properties.setMinimumPlanAmount(100);
        properties.setMaximumPlanAmount(10000);
        rules = new SubscriptionPlanRules(properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void validatesIntegerPlanBoundsAndExpiry() {
        rules.validateDzdAmount(100);
        assertThat(rules.expiryFrom(LocalDateTime.of(2026, 1, 31, 10, 0), BillingPeriod.MONTHLY))
                .isEqualTo(LocalDateTime.of(2026, 2, 28, 10, 0));
        assertThat(rules.expiryFrom(LocalDateTime.of(2026, 1, 1, 10, 0), BillingPeriod.YEARLY))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 10, 0));
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> rules.requireTransition(SubscriptionStatus.CANCELED, SubscriptionStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
    }
}
