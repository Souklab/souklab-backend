package com.project.souklab.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.dto.subscription.SubscriptionCheckoutRequest;
import com.project.souklab.integration.chargily.ChargilyCheckoutClient;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Payment;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.user.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubscriptionCheckoutServiceTest {
    private CurrentUserProvider currentUserProvider;
    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private SubscriptionCheckoutService service;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SubscriptionCheckoutService(
                currentUserProvider,
                mock(SubscriptionPlanRepository.class),
                mock(ArtisanSubscriptionRepository.class),
                mock(ClientSubscriptionRepository.class),
                paymentRepository,
                mock(ChargilyCheckoutClient.class),
                mock(SubscriptionPlanRules.class),
                new ChargilyProperties(),
                new ObjectMapper(),
                mock(NotificationService.class),
                userRepository);
    }

    @Test
    void requiresIdempotencyKeyBeforeAccessingAccountState() {
        assertThatThrownBy(() -> service.checkout(new SubscriptionCheckoutRequest(), " "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Idempotency-Key");
        verifyNoInteractions(currentUserProvider, paymentRepository);
    }

    @Test
    void rejectsIdempotencyKeyReuseForAnotherPlan() {
        User user = new User();
        user.setId("account-1");
        Payment existing = new Payment();
        existing.setPlanSnapshot("{\"planId\":\"plan-a\"}");
        existing.setId("payment-1");
        existing.setSubscriptionId("subscription-1");
        when(currentUserProvider.requireCurrentUser()).thenReturn(user);
        when(userRepository.findWithLockById("account-1")).thenReturn(Optional.of(user));
        when(paymentRepository.findByAccountIdAndIdempotencyKey("account-1", "key-1"))
                .thenReturn(Optional.of(existing));

        SubscriptionCheckoutRequest request = new SubscriptionCheckoutRequest();
        request.setPlanId("plan-b");
        assertThatThrownBy(() -> service.checkout(request, "key-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("another plan");
    }

    @Test
    void refusesCheckoutWhenActivePendingOrActiveSubscriptionExists() {
        ArtisanSubscriptionRepository artisanRepo = mock(ArtisanSubscriptionRepository.class);
        ClientSubscriptionRepository clientRepo = mock(ClientSubscriptionRepository.class);
        SubscriptionPlanRepository planRepo = mock(SubscriptionPlanRepository.class);
        ChargilyProperties props = new ChargilyProperties();
        props.setEnabled(true);

        SubscriptionCheckoutService svc = new SubscriptionCheckoutService(
                currentUserProvider, planRepo, artisanRepo, clientRepo,
                paymentRepository, mock(ChargilyCheckoutClient.class),
                mock(SubscriptionPlanRules.class), props, new ObjectMapper(),
                mock(NotificationService.class), userRepository);

        User user = new User();
        user.setId("account-2");
        Artisan artisan = new Artisan();
        user.setArtisan(artisan);

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId("plan-artisan");
        plan.setSubscriberType(SubscriberType.ARTISAN);
        plan.setActive(true);
        plan.setAmount(1000L);
        plan.setCurrency("DZD");
        plan.setEntitlements(List.of());

        when(currentUserProvider.requireCurrentUser()).thenReturn(user);
        when(userRepository.findWithLockById("account-2")).thenReturn(Optional.of(user));
        when(paymentRepository.findByAccountIdAndIdempotencyKey("account-2", "key-2")).thenReturn(Optional.empty());
        when(planRepo.findById("plan-artisan")).thenReturn(Optional.of(plan));

        when(artisanRepo.countByAccountIdAndStatus("account-2", SubscriptionStatus.ACTIVE)).thenReturn(1L);
        when(artisanRepo.countByAccountIdAndStatus("account-2", SubscriptionStatus.PENDING)).thenReturn(0L);
        SubscriptionCheckoutRequest req = new SubscriptionCheckoutRequest();
        req.setPlanId("plan-artisan");
        assertThatThrownBy(() -> svc.checkout(req, "key-2"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(ex -> assertThat(((BadRequestException) ex).getErrorCode()).isEqualTo(ApiErrorCode.SUBSCRIPTION_ALREADY_PENDING.value()))
                .hasMessageContaining("already active or a payment is already pending");

        when(artisanRepo.countByAccountIdAndStatus("account-2", SubscriptionStatus.ACTIVE)).thenReturn(0L);
        when(artisanRepo.countByAccountIdAndStatus("account-2", SubscriptionStatus.PENDING)).thenReturn(1L);
        assertThatThrownBy(() -> svc.checkout(req, "key-2"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(ex -> assertThat(((BadRequestException) ex).getErrorCode()).isEqualTo(ApiErrorCode.SUBSCRIPTION_ALREADY_PENDING.value()))
                .hasMessageContaining("already active or a payment is already pending");
    }
}
