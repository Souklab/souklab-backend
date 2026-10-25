package com.project.souklab.service.subscription;

import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.model.BillingPeriod;
import com.project.souklab.model.CurrencyCode;
import com.project.souklab.model.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** Pure business rules shared by checkout, webhook, admin, and lifecycle paths. */
@Service
@RequiredArgsConstructor
public class SubscriptionPlanRules {
    private final SubscriptionProperties subscriptionProperties;
    private final Clock clock;

    public void validateDzdAmount(long amount) {
        if (amount <= 0 || amount < subscriptionProperties.getMinimumPlanAmount()
                || amount > subscriptionProperties.getMaximumPlanAmount()) {
            throw new IllegalArgumentException("Plan amount must be a positive DZD amount within configured bounds");
        }
    }

    public void validateCurrency(String currency) {
        if (!CurrencyCode.DZD.matches(currency)) {
            throw new IllegalArgumentException("Subscription currency must be " + CurrencyCode.DZD.value());
        }
    }

    public LocalDateTime expiryFrom(LocalDateTime startsAt, BillingPeriod billingPeriod) {
        if (startsAt == null || billingPeriod == null) {
            throw new IllegalArgumentException("Subscription start and billing period are required");
        }
        return switch (billingPeriod) {
            case MONTHLY -> startsAt.plusMonths(1);
            case YEARLY -> startsAt.plusYears(1);
        };
    }

    public boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now(clock));
    }

    public void requireTransition(SubscriptionStatus from, SubscriptionStatus to) {
        boolean allowed = switch (from) {
            case PENDING -> to == SubscriptionStatus.ACTIVE || to == SubscriptionStatus.CANCELED
                    || to == SubscriptionStatus.EXPIRED || to == SubscriptionStatus.REVOKED;
            case ACTIVE -> to == SubscriptionStatus.CANCELED || to == SubscriptionStatus.EXPIRED
                    || to == SubscriptionStatus.REVOKED;
            case CANCELED, EXPIRED, REVOKED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid subscription transition: " + from + " -> " + to);
        }
    }
}
