package com.project.souklab.service.subscription;

import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.dto.subscription.SubscriptionCheckoutRequest;
import com.project.souklab.dto.subscription.SubscriptionCheckoutResponse;
import com.project.souklab.dto.subscription.SubscriptionPlanSnapshot;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.integration.chargily.ChargilyCheckoutClient;
import com.project.souklab.integration.chargily.ChargilyCheckoutRequest;
import com.project.souklab.integration.chargily.ChargilyCheckoutResponse;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentProvider;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.User;
import com.project.souklab.service.user.CurrentUserProvider;
import com.project.souklab.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SubscriptionCheckoutService {
    private final CurrentUserProvider currentUserProvider;
    private final SubscriptionPlanRepository planRepository;
    private final ArtisanSubscriptionRepository artisanSubscriptionRepository;
    private final ClientSubscriptionRepository clientSubscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final ChargilyCheckoutClient chargilyCheckoutClient;
    private final SubscriptionPlanRules rules;
    private final ChargilyProperties chargilyProperties;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Transactional
    public SubscriptionCheckoutResponse checkout(SubscriptionCheckoutRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new BadRequestException("Idempotency-Key is required");
        User currentUser = currentUserProvider.requireCurrentUser();
        User user = userRepository.findWithLockById(currentUser.getId())
                .orElseThrow(() -> new BadRequestException("Authenticated account no longer exists"));
        return paymentRepository.findByAccountIdAndIdempotencyKey(user.getId(), idempotencyKey).map(existing -> {
            ensureSamePlan(existing, request.getPlanId());
            return toResponse(existing);
        })
                .orElseGet(() -> createCheckout(user, request, idempotencyKey));
    }

    @Transactional
    public SubscriptionCheckoutResponse renew(String subscriptionId, SubscriptionCheckoutRequest request, String idempotencyKey) {
        User user = currentUserProvider.requireCurrentUser();
        SubscriberType targetType = artisanSubscriptionRepository.findById(subscriptionId)
                .filter(value -> value.getAccount().getId().equals(user.getId()))
                .map(value -> SubscriberType.ARTISAN)
                .orElseGet(() -> clientSubscriptionRepository.findById(subscriptionId)
                        .filter(value -> value.getAccount().getId().equals(user.getId()))
                        .map(value -> SubscriberType.CLIENT)
                        .orElse(null));
        if (targetType == null) throw new BadRequestException("Subscription renewal target is invalid");
        SubscriptionPlan requestedPlan = planRepository.findById(request.getPlanId()).filter(SubscriptionPlan::isActive)
                .orElseThrow(() -> new BadRequestException("Subscription plan is not available"));
        if (requestedPlan.getSubscriberType() != targetType) {
            throw new BadRequestException("Renewal plan does not match the subscription type");
        }
        boolean active = hasActiveSubscription(user, targetType);
        if (active) {
            throw new BadRequestException("An active subscription must be canceled or expire before renewal");
        }
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Subscription.RENEWAL, user.getId(), subscriptionId,
                    Map.of(AnalyticsMetadata.Subscription.Plan.ID, requestedPlan.getId(), AnalyticsMetadata.Account.Subscriber.TYPE, targetType));
        }
        return checkout(request, idempotencyKey);
    }

    private SubscriptionCheckoutResponse createCheckout(User user, SubscriptionCheckoutRequest request, String key) {
        SubscriptionPlan plan = planRepository.findById(request.getPlanId()).filter(SubscriptionPlan::isActive)
                .orElseThrow(() -> new BadRequestException("Subscription plan is not available"));
        if (!chargilyProperties.isEnabled()) {
            throw new BadRequestException("Chargily payments are not enabled in this environment");
        }
        rules.validateDzdAmount(plan.getAmount());
        rules.validateCurrency(plan.getCurrency());
        if ((plan.getSubscriberType() == SubscriberType.ARTISAN && user.getArtisan() == null)
                || (plan.getSubscriberType() == SubscriberType.CLIENT && user.getClient() == null)) {
            throw new BadRequestException("The authenticated account does not have the selected subscriber profile");
        }
        boolean hasBlocking = hasBlockingSubscription(user, plan.getSubscriberType());
        if (hasBlocking) {
            throw new BadRequestException(ApiErrorCode.SUBSCRIPTION_ALREADY_PENDING,
                    "A subscription is already active or a payment is already pending");
        }
        String snapshot = snapshot(plan);
        String subscriptionId = createSubscription(user, plan, snapshot);

        Payment payment = new Payment();
        payment.setAccount(user);
        payment.setSubscriptionId(subscriptionId);
        payment.setProvider(PaymentProvider.CHARGILY);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(plan.getAmount());
        payment.setCurrency(plan.getCurrency());
        payment.setPlanSnapshot(snapshot);
        payment.setIdempotencyKey(key);
        paymentRepository.saveAndFlush(payment);

        ChargilyProperties config = chargilyProperties;
        ChargilyCheckoutResponse provider = chargilyCheckoutClient.createCheckout(ChargilyCheckoutRequest.builder()
                .amount(plan.getAmount())
                .currency(plan.getCurrency())
                .successUrl(resolve(request.getSuccessUrl(), config.getSuccessUrl()))
                .failureUrl(resolve(request.getFailureUrl(), config.getFailureUrl()))
                .webhookUrl(config.getWebhookUrl())
                .locale(config.getLocale())
                .feeAllocation(config.getFeeAllocation())
                .metadata(Map.of(
                        AnalyticsMetadata.Payment.Identifier.ID.value(), payment.getId(),
                        AnalyticsMetadata.Provider.Subscription.ID.value(), subscriptionId))
                .build());

        payment.setStatus(PaymentStatus.PENDING);
        payment.setProviderCheckoutId(provider.getId());
        payment.setCheckoutUrl(provider.getCheckoutUrl());
        payment.setProviderCustomerId(provider.getCustomerId());
        payment.setProviderInvoiceId(provider.getInvoiceId());
        try {
            payment.setProviderSnapshot(objectMapper.writeValueAsString(provider));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to snapshot provider checkout", exception);
        }
        Payment saved = paymentRepository.save(payment);
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Checkout.CREATED, user.getId(), saved.getId(),
                    Map.of(AnalyticsMetadata.Payment.State.STATUS, saved.getStatus(), AnalyticsMetadata.Account.Subscriber.TYPE, plan.getSubscriberType()));
        }
        notificationService.createForUser(user, "Your subscription checkout was created.", NotificationType.Checkout.CREATED, saved.getId());
        return toResponse(saved);
    }

    private boolean hasActiveSubscription(User user, SubscriberType targetType) {
        if (targetType == SubscriberType.ARTISAN) {
            return artisanSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) > 0;
        }
        return clientSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) > 0;
    }

    private boolean hasBlockingSubscription(User user, SubscriberType subscriberType) {
        if (subscriberType == SubscriberType.ARTISAN) {
            return artisanSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) > 0
                    || artisanSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.PENDING) > 0;
        }
        return clientSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) > 0
                || clientSubscriptionRepository.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.PENDING) > 0;
    }

    private String createSubscription(User user, SubscriptionPlan plan, String snapshot) {
        if (plan.getSubscriberType() == SubscriberType.ARTISAN) {
            ArtisanSubscription entity = new ArtisanSubscription();
            populate(entity, user, plan, snapshot);
            return artisanSubscriptionRepository.save(entity).getId();
        }
        ClientSubscription entity = new ClientSubscription();
        populate(entity, user, plan, snapshot);
        return clientSubscriptionRepository.save(entity).getId();
    }

    private void populate(ArtisanSubscription entity, User user, SubscriptionPlan plan, String snapshot) {
        entity.setAccount(user);
        entity.setStatus(SubscriptionStatus.PENDING);
        entity.setPlanId(plan.getId());
        entity.setPlanName(plan.getName());
        entity.setBillingPeriod(plan.getBillingPeriod());
        entity.setAmount(plan.getAmount());
        entity.setCurrency(plan.getCurrency());
        entity.setEntitlementsSnapshot(snapshot);
    }

    private void populate(ClientSubscription entity, User user, SubscriptionPlan plan, String snapshot) {
        entity.setAccount(user);
        entity.setStatus(SubscriptionStatus.PENDING);
        entity.setPlanId(plan.getId());
        entity.setPlanName(plan.getName());
        entity.setBillingPeriod(plan.getBillingPeriod());
        entity.setAmount(plan.getAmount());
        entity.setCurrency(plan.getCurrency());
        entity.setEntitlementsSnapshot(snapshot);
    }

    private String snapshot(SubscriptionPlan plan) {
        Map<String, String> entitlements = plan.getEntitlements().stream().collect(LinkedHashMap::new,
                (map, entitlement) -> map.put(entitlement.getKey(), entitlement.getValue()), LinkedHashMap::putAll);
        try {
            return objectMapper.writeValueAsString(SubscriptionPlanSnapshot.builder().planId(plan.getId()).name(plan.getName()).subscriberType(plan.getSubscriberType())
                    .billingPeriod(plan.getBillingPeriod()).amount(plan.getAmount()).currency(plan.getCurrency()).entitlements(entitlements).build());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to snapshot subscription plan", exception);
        }
    }

    private String resolve(String requested, String configured) {
        return requested == null || requested.isBlank() ? configured : requested;
    }

    private void ensureSamePlan(Payment payment, String planId) {
        try {
            String existingPlan = objectMapper.readTree(payment.getPlanSnapshot())
                    .path(AnalyticsMetadata.Subscription.Plan.ID.value()).asText();
            if (!planId.equals(existingPlan)) {
                throw new BadRequestException("Idempotency-Key was already used for another plan");
            }
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Existing idempotent payment snapshot is invalid", exception);
        }
    }

    private SubscriptionCheckoutResponse toResponse(Payment payment) {
        return SubscriptionCheckoutResponse.builder()
                .paymentId(payment.getId())
                .subscriptionId(payment.getSubscriptionId())
                .providerCheckoutId(payment.getProviderCheckoutId())
                .checkoutUrl(payment.getCheckoutUrl())
                .build();
    }
}
