package com.project.souklab.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.PaymentWebhookLogRepository;
import com.project.souklab.model.PaymentWebhookLog;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.WebhookProcessingStatus;
import com.project.souklab.model.User;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChargilyWebhookServiceTest {
    private ChargilyWebhookService service;
    private WebhookEventClaimService claimService;
    private PaymentWebhookLogRepository logRepository;
    private PaymentRepository paymentRepository;
    private ClientSubscriptionRepository clientSubscriptionRepository;
    private AuditLogService auditLogService;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        ChargilyProperties chargilyProperties = new ChargilyProperties();
        secret = "webhook-secret".getBytes(StandardCharsets.UTF_8);
        chargilyProperties.setSecretKey(new String(secret, StandardCharsets.UTF_8));
        chargilyProperties.setWebhookEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));

        SubscriptionProperties subscriptionProperties = new SubscriptionProperties();
        subscriptionProperties.setWebhookRetention(Duration.ofDays(90));
        subscriptionProperties.setLifecycleInterval(Duration.ofHours(1));

        Clock clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);
        logRepository = mock(PaymentWebhookLogRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        clientSubscriptionRepository = mock(ClientSubscriptionRepository.class);
        claimService = mock(WebhookEventClaimService.class);
        auditLogService = mock(AuditLogService.class);
        service = new ChargilyWebhookService(
                new ObjectMapper(), new WebhookSecurityService(chargilyProperties), logRepository,
                paymentRepository, mock(ArtisanSubscriptionRepository.class),
                clientSubscriptionRepository, mock(SubscriptionPlanRules.class),
                mock(NotificationService.class), claimService, subscriptionProperties, clock,
                auditLogService);
    }

    @Test
    void rejectsIncompleteAndStaleEventsBeforeClaiming() throws Exception {
        byte[] incomplete = "{\"id\":\"evt-1\",\"type\":\"checkout.paid\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.process(incomplete, signature(incomplete)))
                .isInstanceOf(MalformedWebhookException.class);

        byte[] stale = ("{\"id\":\"evt-2\",\"type\":\"checkout.paid\",\"created_at\":"
                + Instant.parse("2020-01-01T00:00:00Z").getEpochSecond()
                + ",\"data\":{\"id\":\"checkout-2\"}}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.process(stale, signature(stale)))
                .isInstanceOf(MalformedWebhookException.class);
        verify(claimService, never()).claim(any(), any(), any(), any());
    }

    @Test
    void validUnknownCheckoutIsClaimedAndIgnoredWithoutActivation() throws Exception {
        byte[] body = ("{\"id\":\"evt-3\",\"entity\":\"event\",\"livemode\":false,\"updated_at\":1790000000,\"type\":\"checkout.paid\",\"created_at\":"
                + Instant.parse("2026-09-17T23:00:00Z").getEpochSecond()
                + ",\"data\":{\"id\":\"unknown-checkout\"}}").getBytes(StandardCharsets.UTF_8);
        PaymentWebhookLog log = new PaymentWebhookLog();
        log.setStatus(WebhookProcessingStatus.RECEIVED);
        when(claimService.claim("evt-3", ChargilyWebhookEvent.Checkout.PAID, "unknown-checkout", body)).thenReturn(true);
        when(claimService.acquireProcessing(any(), any())).thenReturn(true);
        when(logRepository.findByProviderEventId("evt-3")).thenReturn(Optional.of(log));

        service.process(body, signature(body));

        verify(claimService).claim("evt-3", ChargilyWebhookEvent.Checkout.PAID, "unknown-checkout", body);
        assertThat(log.getStatus()).isEqualTo(WebhookProcessingStatus.IGNORED);
    }

    @Test
    void failedCheckoutCancelsPendingSubscription() throws Exception {
        byte[] body = ("{\"id\":\"evt-4\",\"type\":\"checkout.failed\",\"created_at\":"
                + Instant.parse("2026-09-17T23:00:00Z").getEpochSecond()
                + ",\"data\":{\"id\":\"checkout-4\"}}").getBytes(StandardCharsets.UTF_8);
        PaymentWebhookLog log = new PaymentWebhookLog();
        log.setStatus(WebhookProcessingStatus.RECEIVED);
        Payment payment = new Payment();
        payment.setSubscriptionId("subscription-4");
        User account = new User();
        account.setId("account-4");
        payment.setAccount(account);
        ClientSubscription subscription = new ClientSubscription();
        subscription.setStatus(SubscriptionStatus.PENDING);
        when(claimService.claim("evt-4", ChargilyWebhookEvent.Checkout.FAILED, "checkout-4", body)).thenReturn(true);
        when(claimService.acquireProcessing(any(), any())).thenReturn(true);
        when(logRepository.findByProviderEventId("evt-4")).thenReturn(Optional.of(log));
        when(paymentRepository.findByProviderCheckoutId("checkout-4")).thenReturn(Optional.of(payment));
        when(clientSubscriptionRepository.findWithLockById("subscription-4")).thenReturn(Optional.of(subscription));

        service.process(body, signature(body));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(log.getStatus()).isEqualTo(WebhookProcessingStatus.PROCESSED);
        verify(auditLogService).logFinancialState(eq(AuditLogAction.Payment.State.FAILED), eq(account),
                eq("account-4"), any(), isNull(), eq(PaymentStatus.FAILED), any(), any(), eq("subscription-4"));
    }

    @Test
    void duplicateProcessedDeliveryDoesNotAcquireOrProcessAgain() throws Exception {
        byte[] body = ("{\"id\":\"evt-5\",\"type\":\"checkout.paid\",\"created_at\":"
                + Instant.parse("2026-09-17T23:00:00Z").getEpochSecond()
                + ",\"data\":{\"id\":\"checkout-5\"}}").getBytes(StandardCharsets.UTF_8);
        PaymentWebhookLog log = new PaymentWebhookLog();
        log.setStatus(WebhookProcessingStatus.PROCESSED);
        when(claimService.claim("evt-5", ChargilyWebhookEvent.Checkout.PAID, "checkout-5", body)).thenReturn(false);
        when(logRepository.findByProviderEventId("evt-5")).thenReturn(Optional.of(log));

        service.process(body, signature(body));

        verify(claimService, never()).acquireProcessing(any(), any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void skipsActivationAndCancelsDuplicateWhenActiveSubscriptionAlreadyExists() throws Exception {
        ArtisanSubscriptionRepository artisanRepo = mock(ArtisanSubscriptionRepository.class);

        ChargilyProperties chargilyProperties = new ChargilyProperties();
        chargilyProperties.setSecretKey(new String(secret, StandardCharsets.UTF_8));
        chargilyProperties.setWebhookEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        SubscriptionProperties subscriptionProperties = new SubscriptionProperties();
        subscriptionProperties.setWebhookRetention(Duration.ofDays(90));
        subscriptionProperties.setLifecycleInterval(Duration.ofHours(1));
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);

        SubscriptionPlanRules rules = mock(SubscriptionPlanRules.class);
        ChargilyWebhookService svc = new ChargilyWebhookService(
                new ObjectMapper(), new WebhookSecurityService(chargilyProperties), logRepository,
                paymentRepository, artisanRepo, mock(ClientSubscriptionRepository.class),
                rules, mock(NotificationService.class), claimService, subscriptionProperties, clock,
                auditLogService);

        byte[] body = ("{\"id\":\"evt-6\",\"type\":\"checkout.paid\",\"created_at\":"
                + Instant.parse("2026-09-17T23:00:00Z").getEpochSecond()
                + ",\"data\":{\"id\":\"checkout-6\"}}").getBytes(StandardCharsets.UTF_8);
        PaymentWebhookLog webhookLog = new PaymentWebhookLog();
        webhookLog.setStatus(WebhookProcessingStatus.RECEIVED);

        Payment payment = new Payment();
        payment.setSubscriptionId("sub-6");
        User account = new User();
        account.setId("account-6");
        payment.setAccount(account);

        ArtisanSubscription subscription = new ArtisanSubscription();
        subscription.setStatus(SubscriptionStatus.PENDING);
        subscription.setAccount(account);

        when(claimService.claim("evt-6", ChargilyWebhookEvent.Checkout.PAID, "checkout-6", body)).thenReturn(true);
        when(claimService.acquireProcessing(any(), any())).thenReturn(true);
        when(logRepository.findByProviderEventId("evt-6")).thenReturn(Optional.of(webhookLog));
        when(paymentRepository.findByProviderCheckoutId("checkout-6")).thenReturn(Optional.of(payment));
        when(artisanRepo.findWithLockById("sub-6")).thenReturn(Optional.of(subscription));
        when(artisanRepo.countByAccountIdAndStatus("account-6", SubscriptionStatus.ACTIVE)).thenReturn(1L);

        svc.process(body, signature(body));

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        verify(rules).requireTransition(SubscriptionStatus.PENDING, SubscriptionStatus.CANCELED);
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        StringBuilder result = new StringBuilder();
        for (byte value : mac.doFinal(body)) result.append(String.format("%02x", value));
        return result.toString();
    }
}
