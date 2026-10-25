package com.project.souklab.service.subscription;

import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.PaymentWebhookLogRepository;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionLifecycleServiceTest {
    @Test
    void expiresStalePendingPaymentAndItsPendingSubscription() {
        SubscriptionProperties properties = new SubscriptionProperties();
        properties.setLifecycleInterval(Duration.ofHours(1));
        properties.setLifecycleBatchSize(10);
        properties.setCheckoutIdempotencyRetention(Duration.ofHours(24));
        properties.setWebhookRetention(Duration.ofDays(90));
        properties.setReminderOffsets(List.of(7L, 1L));
        properties.setLifecycleTimeZone("UTC");

        Payment payment = new Payment();
        payment.setId("payment-1");
        payment.setSubscriptionId("subscription-1");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAccount(new User());
        payment.setCreatedAt(LocalDateTime.of(2026, 9, 16, 0, 0));

        ArtisanSubscription subscription = new ArtisanSubscription();
        subscription.setStatus(SubscriptionStatus.PENDING);

        PaymentRepository payments = mock(PaymentRepository.class);
        ArtisanSubscriptionRepository artisans = mock(ArtisanSubscriptionRepository.class);
        ClientSubscriptionRepository clients = mock(ClientSubscriptionRepository.class);
        when(payments.findByStatusOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(payment));
        when(artisans.findWithLockById("subscription-1")).thenReturn(Optional.of(subscription));

        SubscriptionLifecycleService service = new SubscriptionLifecycleService(
                payments, artisans, clients, mock(NotificationService.class),
                mock(PaymentWebhookLogRepository.class), properties,
                Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC));

        service.processLifecycle();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    }
}
