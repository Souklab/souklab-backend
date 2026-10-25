package com.project.souklab.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.subscription.FinancialReasonRequest;
import com.project.souklab.dto.subscription.ManualSubscriptionGrantRequest;
import com.project.souklab.dto.subscription.PaymentStateCorrectionRequest;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.BillingPeriod;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.user.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSubscriptionServiceTest {

    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private UserRepository userRepository;
    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private ArtisanSubscriptionRepository artisanSubscriptions;
    @Mock private ClientSubscriptionRepository clientSubscriptions;
    @Mock private PaymentRepository payments;
    @Mock private SubscriptionPlanRules rules;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationService notificationService;

    private AdminSubscriptionService service;
    private Clock clock;
    private User adminActor;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);
        adminActor = new User();
        adminActor.setId("admin-actor-1");

        service = new AdminSubscriptionService(
                currentUserProvider, userRepository, planRepository,
                artisanSubscriptions, clientSubscriptions, payments,
                rules, auditLogService, notificationService,
                new ObjectMapper(), clock
        );
    }

    @Test
    void paidPaymentCorrectionActivatesSubscriptionAndPremiumCompatibilityFlag() {
        User account = new User();
        account.setId("account-1");
        Client client = new Client();
        account.setClient(client);

        Payment payment = new Payment();
        payment.setId("payment-1");
        payment.setAccount(account);
        payment.setSubscriptionId("subscription-1");
        payment.setStatus(PaymentStatus.PENDING);

        ClientSubscription subscription = new ClientSubscription();
        subscription.setAccount(account);
        subscription.setStatus(SubscriptionStatus.PENDING);
        subscription.setBillingPeriod(BillingPeriod.MONTHLY);

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(payments.findById("payment-1")).thenReturn(Optional.of(payment));
        when(artisanSubscriptions.findWithLockById("subscription-1")).thenReturn(Optional.empty());
        when(clientSubscriptions.findWithLockById("subscription-1")).thenReturn(Optional.of(subscription));
        when(rules.expiryFrom(any(LocalDateTime.class), any())).thenReturn(LocalDateTime.of(2026, 10, 18, 0, 0));

        PaymentStateCorrectionRequest request = new PaymentStateCorrectionRequest();
        request.setStatus(PaymentStatus.PAID);
        request.setReason("Verified bank settlement");

        service.correctPayment("payment-1", request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
        assertThat(client.isPremium()).isTrue();
    }

    @Test
    void grantArtisanSubscriptionSuccess() {
        User target = new User();
        target.setId("target-artisan-id");
        Artisan artisan = new Artisan();
        artisan.setPremium(false);
        target.setArtisan(artisan);

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId("plan-artisan-1");
        plan.setName("Pro Artisan Plan");
        plan.setSubscriberType(SubscriberType.ARTISAN);
        plan.setBillingPeriod(BillingPeriod.MONTHLY);
        plan.setAmount(3000);
        plan.setCurrency("DZD");
        plan.setActive(true);
        plan.setEntitlements(new ArrayList<>());

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(userRepository.findWithLockById("target-artisan-id")).thenReturn(Optional.of(target));
        when(planRepository.findById("plan-artisan-1")).thenReturn(Optional.of(plan));
        when(artisanSubscriptions.countByAccountIdAndStatus("target-artisan-id", SubscriptionStatus.ACTIVE)).thenReturn(0L);
        when(rules.expiryFrom(any(LocalDateTime.class), eq(BillingPeriod.MONTHLY))).thenReturn(LocalDateTime.of(2026, 10, 18, 0, 0));
        when(artisanSubscriptions.save(any(ArtisanSubscription.class))).thenAnswer(inv -> {
            ArtisanSubscription s = inv.getArgument(0);
            s.setId("sub-artisan-1");
            s.setCreatedAt(LocalDateTime.of(2026, 9, 18, 0, 0));
            return s;
        });

        ManualSubscriptionGrantRequest request = new ManualSubscriptionGrantRequest();
        request.setAccountId("target-artisan-id");
        request.setPlanId("plan-artisan-1");
        request.setReason("Promotional grant for featured artisan");

        SubscriptionResponse response = service.grant(request);

        assertThat(response.getId()).isEqualTo("sub-artisan-1");
        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(response.getSubscriberType()).isEqualTo(SubscriberType.ARTISAN);
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
        assertThat(artisan.isPremium()).isTrue();

        verify(payments).save(any(Payment.class));
        verify(notificationService).createForUser(eq(target), any(), any(), eq("sub-artisan-1"));
    }

    @Test
    void grantClientSubscriptionSuccess() {
        User target = new User();
        target.setId("target-client-id");
        Client client = new Client();
        client.setPremium(false);
        target.setClient(client);

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId("plan-client-1");
        plan.setName("VIP Client Plan");
        plan.setSubscriberType(SubscriberType.CLIENT);
        plan.setBillingPeriod(BillingPeriod.YEARLY);
        plan.setAmount(10000);
        plan.setCurrency("DZD");
        plan.setActive(true);
        plan.setEntitlements(new ArrayList<>());

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(userRepository.findWithLockById("target-client-id")).thenReturn(Optional.of(target));
        when(planRepository.findById("plan-client-1")).thenReturn(Optional.of(plan));
        when(clientSubscriptions.countByAccountIdAndStatus("target-client-id", SubscriptionStatus.ACTIVE)).thenReturn(0L);
        when(rules.expiryFrom(any(LocalDateTime.class), eq(BillingPeriod.YEARLY))).thenReturn(LocalDateTime.of(2027, 9, 18, 0, 0));
        when(clientSubscriptions.save(any(ClientSubscription.class))).thenAnswer(inv -> {
            ClientSubscription s = inv.getArgument(0);
            s.setId("sub-client-1");
            s.setCreatedAt(LocalDateTime.of(2026, 9, 18, 0, 0));
            return s;
        });

        ManualSubscriptionGrantRequest request = new ManualSubscriptionGrantRequest();
        request.setAccountId("target-client-id");
        request.setPlanId("plan-client-1");
        request.setReason("Partner VIP access");

        SubscriptionResponse response = service.grant(request);

        assertThat(response.getId()).isEqualTo("sub-client-1");
        assertThat(response.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(response.getSubscriberType()).isEqualTo(SubscriberType.CLIENT);
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
        assertThat(client.isPremium()).isTrue();
    }

    @Test
    void grantRejectsWhenAlreadyActiveSubscription() {
        User target = new User();
        target.setId("target-artisan-id");
        Artisan artisan = new Artisan();
        target.setArtisan(artisan);

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId("plan-1");
        plan.setSubscriberType(SubscriberType.ARTISAN);
        plan.setActive(true);

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(userRepository.findWithLockById("target-artisan-id")).thenReturn(Optional.of(target));
        when(planRepository.findById("plan-1")).thenReturn(Optional.of(plan));
        when(artisanSubscriptions.countByAccountIdAndStatus("target-artisan-id", SubscriptionStatus.ACTIVE)).thenReturn(1L);

        ManualSubscriptionGrantRequest request = new ManualSubscriptionGrantRequest();
        request.setAccountId("target-artisan-id");
        request.setPlanId("plan-1");

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Target already has an active subscription");
    }

    @Test
    void grantRejectsWhenProfileTypeMismatch() {
        User target = new User();
        target.setId("target-user-no-artisan");
        target.setArtisan(null);

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId("plan-artisan");
        plan.setSubscriberType(SubscriberType.ARTISAN);
        plan.setActive(true);

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(userRepository.findWithLockById("target-user-no-artisan")).thenReturn(Optional.of(target));
        when(planRepository.findById("plan-artisan")).thenReturn(Optional.of(plan));

        ManualSubscriptionGrantRequest request = new ManualSubscriptionGrantRequest();
        request.setAccountId("target-user-no-artisan");
        request.setPlanId("plan-artisan");

        assertThatThrownBy(() -> service.grant(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not have an artisan profile");
    }

    @Test
    void revokeArtisanSubscriptionSuccess() {
        User account = new User();
        account.setId("artisan-user-1");
        Artisan artisan = new Artisan();
        artisan.setPremium(true);
        account.setArtisan(artisan);

        ArtisanSubscription subscription = new ArtisanSubscription();
        subscription.setId("sub-artisan-1");
        subscription.setAccount(account);
        subscription.setStatus(SubscriptionStatus.ACTIVE);

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(artisanSubscriptions.findWithLockById("sub-artisan-1")).thenReturn(Optional.of(subscription));
        when(payments.findBySubscriptionIdAndStatus("sub-artisan-1", PaymentStatus.PENDING)).thenReturn(Collections.emptyList());

        FinancialReasonRequest request = new FinancialReasonRequest();
        request.setReason("Violation of terms");

        service.revoke("sub-artisan-1", request);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
        assertThat(artisan.isPremium()).isFalse();

        verify(rules).requireTransition(SubscriptionStatus.ACTIVE, SubscriptionStatus.REVOKED);
        verify(notificationService).createForUser(eq(account), any(), any(), eq("sub-artisan-1"));
    }

    @Test
    void cancelArtisanSubscriptionSuccess() {
        User account = new User();
        account.setId("artisan-user-1");
        Artisan artisan = new Artisan();
        artisan.setPremium(true);
        account.setArtisan(artisan);

        ArtisanSubscription subscription = new ArtisanSubscription();
        subscription.setId("sub-artisan-1");
        subscription.setAccount(account);
        subscription.setStatus(SubscriptionStatus.ACTIVE);

        when(currentUserProvider.requireCurrentUser()).thenReturn(adminActor);
        when(artisanSubscriptions.findWithLockById("sub-artisan-1")).thenReturn(Optional.of(subscription));
        when(payments.findBySubscriptionIdAndStatus("sub-artisan-1", PaymentStatus.PENDING)).thenReturn(Collections.emptyList());

        FinancialReasonRequest request = new FinancialReasonRequest();
        request.setReason("User requested cancellation");

        service.cancel("sub-artisan-1", request);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(artisan.isPremium()).isFalse();

        verify(rules).requireTransition(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED);
    }
}
