package com.project.souklab.service.subscription;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.PaymentWebhookLogRepository;
import com.project.souklab.model.PaymentWebhookLog;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.WebhookProcessingStatus;
import com.project.souklab.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleService {
    private final PaymentRepository paymentRepository;
    private final ArtisanSubscriptionRepository artisanSubscriptions;
    private final ClientSubscriptionRepository clientSubscriptions;
    private final NotificationService notificationService;
    private final PaymentWebhookLogRepository webhookLogs;
    private final SubscriptionProperties subscriptionProperties;
    private final Clock clock;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Scheduled(fixedDelayString = "${app.subscription.lifecycle-interval}")
    @Transactional
    public void processLifecycle() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of(subscriptionProperties.getLifecycleTimeZone())));
        expirePendingPayments(now);
        PageRequest batch = PageRequest.of(0, subscriptionProperties.getLifecycleBatchSize());
        artisanSubscriptions.findByStatusAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now, batch).forEach(this::expire);
        clientSubscriptions.findByStatusAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now, batch).forEach(this::expire);
        sendReminders(now);
        releaseStaleWebhookClaims(now);
        releaseExpiredWebhookLogs(now);
    }

    private void releaseStaleWebhookClaims(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(subscriptionProperties.getLifecycleInterval());
        PageRequest batch = PageRequest.of(0, subscriptionProperties.getLifecycleBatchSize());
        webhookLogs.findByStatusAndUpdatedAtBefore(WebhookProcessingStatus.PROCESSING, cutoff, batch).forEach(log -> {
            log.setStatus(WebhookProcessingStatus.FAILED);
            log.setFailureReason("Stale webhook processing claim released");
        });
    }

    private void releaseExpiredWebhookLogs(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(subscriptionProperties.getWebhookRetention());
        int batchSize = subscriptionProperties.getLifecycleBatchSize();
        for (PaymentWebhookLog log : webhookLogs.findByCreatedAtBefore(cutoff, PageRequest.of(0, batchSize))) {
            webhookLogs.delete(log);
        }
    }

    private void expirePendingPayments(LocalDateTime now) {
        int batchSize = subscriptionProperties.getLifecycleBatchSize();
        LocalDateTime cutoff = now.minus(subscriptionProperties.getCheckoutIdempotencyRetention());
        for (Payment payment : paymentRepository.findByStatusOrderByCreatedAtAsc(PaymentStatus.PENDING, PageRequest.of(0, batchSize))) {
            if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(cutoff)) {
                payment.setStatus(PaymentStatus.EXPIRED);
                if (activityEventService != null) activityEventService.record(AnalyticsEvent.Payment.State.TRANSITION,
                        payment.getAccount().getId(), payment.getId(), Map.of(AnalyticsMetadata.State.STATUS, PaymentStatus.EXPIRED));
                expirePendingSubscription(payment.getSubscriptionId());
                notificationService.createForUser(payment.getAccount(), "Your subscription checkout expired.", NotificationType.Checkout.CANCELED, payment.getId());
            }
        }
    }

    private void expirePendingSubscription(String subscriptionId) {
        artisanSubscriptions.findWithLockById(subscriptionId).ifPresent(subscription -> {
            if (subscription.getStatus() == SubscriptionStatus.PENDING) {
                subscription.setStatus(SubscriptionStatus.EXPIRED);
                recordSubscriptionExpiry(subscription);
            }
        });
        clientSubscriptions.findWithLockById(subscriptionId).ifPresent(subscription -> {
            if (subscription.getStatus() == SubscriptionStatus.PENDING) {
                subscription.setStatus(SubscriptionStatus.EXPIRED);
                recordSubscriptionExpiry(subscription);
            }
        });
    }

    private void expire(ArtisanSubscription subscription) {
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        recordSubscriptionExpiry(subscription);
        if (subscription.getAccount().getArtisan() != null && artisanSubscriptions.countByAccountIdAndStatus(subscription.getAccount().getId(), SubscriptionStatus.ACTIVE) == 0) {
            subscription.getAccount().getArtisan().setPremium(false);
        }
        notificationService.createForUser(subscription.getAccount(), "Your artisan subscription has expired.", NotificationType.Subscription.EXPIRED, subscription.getId());
    }

    private void expire(ClientSubscription subscription) {
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        recordSubscriptionExpiry(subscription);
        if (subscription.getAccount().getClient() != null && clientSubscriptions.countByAccountIdAndStatus(subscription.getAccount().getId(), SubscriptionStatus.ACTIVE) == 0) {
            subscription.getAccount().getClient().setPremium(false);
        }
        notificationService.createForUser(subscription.getAccount(), "Your client subscription has expired.", NotificationType.Subscription.EXPIRED, subscription.getId());
    }

    private void sendReminders(LocalDateTime now) {
        subscriptionProperties.getReminderOffsets().forEach(offset -> {
            PageRequest batch = PageRequest.of(0, subscriptionProperties.getLifecycleBatchSize());
            artisanSubscriptions.findByStatusAndExpiresAtAfterAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now, now.plusDays(offset), batch).forEach(value -> remind(value, offset, now));
            clientSubscriptions.findByStatusAndExpiresAtAfterAndExpiresAtBefore(SubscriptionStatus.ACTIVE, now, now.plusDays(offset), batch).forEach(value -> remind(value, offset, now));
        });
    }

    private void remind(ArtisanSubscription subscription, long offset, LocalDateTime now) {
        if (subscription.getExpiresAt() == null || subscription.getExpiresAt().isBefore(now) || sent(subscription.getReminderOffsetsSent(), offset)) return;
        subscription.setReminderOffsetsSent(append(subscription.getReminderOffsetsSent(), offset));
        notificationService.createForUser(subscription.getAccount(), "Your subscription expires in " + offset + " day(s).", NotificationType.Subscription.Renewal.REMINDER, subscription.getId());
    }

    private void remind(ClientSubscription subscription, long offset, LocalDateTime now) {
        if (subscription.getExpiresAt() == null || subscription.getExpiresAt().isBefore(now) || sent(subscription.getReminderOffsetsSent(), offset)) return;
        subscription.setReminderOffsetsSent(append(subscription.getReminderOffsetsSent(), offset));
        notificationService.createForUser(subscription.getAccount(), "Your subscription expires in " + offset + " day(s).", NotificationType.Subscription.Renewal.REMINDER, subscription.getId());
    }

    private void recordSubscriptionExpiry(ArtisanSubscription subscription) {
        if (activityEventService != null && subscription.getAccount() != null) activityEventService.record(AnalyticsEvent.Subscription.EXPIRED, subscription.getAccount().getId(), subscription.getId(),
                Map.of(AnalyticsMetadata.State.STATUS, SubscriptionStatus.EXPIRED));
    }

    private void recordSubscriptionExpiry(ClientSubscription subscription) {
        if (activityEventService != null && subscription.getAccount() != null) activityEventService.record(AnalyticsEvent.Subscription.EXPIRED, subscription.getAccount().getId(), subscription.getId(),
                Map.of(AnalyticsMetadata.State.STATUS, SubscriptionStatus.EXPIRED));
    }

    private boolean sent(String value, long offset) {
        return value != null && Set.of(value.split(",")).contains(Long.toString(offset));
    }

    private String append(String value, long offset) {
        return value == null || value.isBlank() ? Long.toString(offset) : value + "," + offset;
    }
}
