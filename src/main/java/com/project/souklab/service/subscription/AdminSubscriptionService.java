package com.project.souklab.service.subscription;

import com.project.souklab.model.AccountRole;

import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.dto.subscription.FinancialReasonRequest;
import com.project.souklab.dto.subscription.PaymentStateCorrectionRequest;
import com.project.souklab.dto.subscription.SubscriptionStateCorrectionRequest;
import com.project.souklab.dto.subscription.ManualSubscriptionGrantRequest;
import com.project.souklab.dto.subscription.SubscriptionPlanSnapshot;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.dto.subscription.PaymentResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.FinancialAuditOperation;
import com.project.souklab.model.FinancialAuditState;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentProvider;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.model.NotificationType;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.user.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class AdminSubscriptionService {
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository planRepository;
    private final ArtisanSubscriptionRepository artisanSubscriptions;
    private final ClientSubscriptionRepository clientSubscriptions;
    private final PaymentRepository payments;
    private final SubscriptionPlanRules rules;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Transactional
    public SubscriptionResponse grant(ManualSubscriptionGrantRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        User target = userRepository.findWithLockById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Target account not found"));
        SubscriptionPlan plan = planRepository.findById(request.getPlanId()).filter(SubscriptionPlan::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        if (plan.getSubscriberType() == SubscriberType.ARTISAN && target.getArtisan() == null) {
            throw new BadRequestException("Target account does not have an artisan profile");
        }
        if (plan.getSubscriberType() == SubscriberType.CLIENT && target.getClient() == null) {
            throw new BadRequestException("Target account does not have a client profile");
        }
        LocalDateTime starts = LocalDateTime.now(clock);
        String snapshot = snapshot(plan);
        SubscriptionResponse response;
        String subscriptionId;
        if (plan.getSubscriberType() == SubscriberType.ARTISAN) {
            if (artisanSubscriptions.countByAccountIdAndStatus(target.getId(), SubscriptionStatus.ACTIVE) > 0) throw new BadRequestException("Target already has an active subscription");
            ArtisanSubscription subscription = new ArtisanSubscription();
            populate(subscription, target, plan, snapshot, starts);
            ArtisanSubscription saved = artisanSubscriptions.save(subscription); subscriptionId = saved.getId();
            target.getArtisan().setPremium(true); response = toResponse(saved, SubscriberType.ARTISAN);
        } else {
            if (clientSubscriptions.countByAccountIdAndStatus(target.getId(), SubscriptionStatus.ACTIVE) > 0) throw new BadRequestException("Target already has an active subscription");
            ClientSubscription subscription = new ClientSubscription();
            populate(subscription, target, plan, snapshot, starts);
            ClientSubscription saved = clientSubscriptions.save(subscription); subscriptionId = saved.getId();
            target.getClient().setPremium(true); response = toResponse(saved, SubscriberType.CLIENT);
        }
        Payment payment = new Payment(); payment.setAccount(target); payment.setSubscriptionId(subscriptionId); payment.setProvider(PaymentProvider.CHARGILY);
        payment.setStatus(PaymentStatus.MANUALLY_GRANTED); payment.setManualGrant(true); payment.setAmount(plan.getAmount()); payment.setCurrency(plan.getCurrency());
        payment.setPlanSnapshot(snapshot); payment.setIdempotencyKey("manual-" + subscriptionId); payments.save(payment);
        auditLogService.logFinancialState(AuditLogAction.Subscription.GRANTED, actor, target.getId(), FinancialAuditOperation.Manual.GRANT, FinancialAuditState.NONE, SubscriptionStatus.ACTIVE, request.getReason(), payment.getId(), subscriptionId);
        notificationService.createForUser(target, "A subscription was manually granted to your account.", NotificationType.Subscription.Grant.MANUAL, subscriptionId);
        return response;
    }

    @Transactional
    public void revoke(String subscriptionId, FinancialReasonRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        artisanSubscriptions.findWithLockById(subscriptionId).ifPresentOrElse(subscription -> {
            rules.requireTransition(subscription.getStatus(), SubscriptionStatus.REVOKED);
            SubscriptionStatus previous = subscription.getStatus(); subscription.setStatus(SubscriptionStatus.REVOKED);
            recordSubscriptionEvent(subscription.getAccount(), subscription.getId(), AnalyticsEvent.Subscription.REVOKED);
            cancelPendingPayments(subscriptionId);
            if (subscription.getAccount().getAccountRole() == AccountRole.ARTISAN
                    && subscription.getAccount().getArtisan() != null) {
                subscription.getAccount().getArtisan().setPremium(false);
            }
            auditLogService.logFinancialState(AuditLogAction.Subscription.REVOKED, actor, subscription.getAccount().getId(), FinancialAuditOperation.Subscription.REVOKE, previous, SubscriptionStatus.REVOKED, request.getReason(), null, subscriptionId);
            notificationService.createForUser(subscription.getAccount(), "Your subscription was revoked.", NotificationType.Subscription.REVOKED, subscriptionId);
        }, () -> clientSubscriptions.findWithLockById(subscriptionId).ifPresentOrElse(subscription -> {
            rules.requireTransition(subscription.getStatus(), SubscriptionStatus.REVOKED);
            SubscriptionStatus previous = subscription.getStatus(); subscription.setStatus(SubscriptionStatus.REVOKED);
            recordSubscriptionEvent(subscription.getAccount(), subscription.getId(), AnalyticsEvent.Subscription.REVOKED);
            cancelPendingPayments(subscriptionId);
            if (subscription.getAccount().getAccountRole() == AccountRole.CLIENT
                    && subscription.getAccount().getClient() != null) {
                subscription.getAccount().getClient().setPremium(false);
            }
            auditLogService.logFinancialState(AuditLogAction.Subscription.REVOKED, actor, subscription.getAccount().getId(), FinancialAuditOperation.Subscription.REVOKE, previous, SubscriptionStatus.REVOKED, request.getReason(), null, subscriptionId);
            notificationService.createForUser(subscription.getAccount(), "Your subscription was revoked.", NotificationType.Subscription.REVOKED, subscriptionId);
        }, () -> { throw new ResourceNotFoundException("Subscription not found"); }));
    }

    @Transactional
    public void correctPayment(String paymentId, PaymentStateCorrectionRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        Payment payment = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        PaymentStatus corrected = request.getStatus();
        PaymentStatus previous = payment.getStatus();
        payment.setStatus(corrected);
        recordPaymentTransition(payment, previous, corrected);
        synchronizeCorrectedPayment(payment, corrected);
        auditLogService.logFinancialState(AuditLogAction.Payment.State.CORRECTED, actor, payment.getAccount().getId(), FinancialAuditOperation.State.CORRECTION, previous, corrected, request.getReason(), payment.getId(), payment.getSubscriptionId());
    }

    private void synchronizeCorrectedPayment(Payment payment, PaymentStatus corrected) {
        if (corrected == PaymentStatus.PAID) {
            artisanSubscriptions.findWithLockById(payment.getSubscriptionId()).ifPresent(this::activateCorrectedArtisan);
            clientSubscriptions.findWithLockById(payment.getSubscriptionId()).ifPresent(this::activateCorrectedClient);
            return;
        }
        if (corrected != PaymentStatus.FAILED && corrected != PaymentStatus.CANCELED
                && corrected != PaymentStatus.EXPIRED) {
            return;
        }
        SubscriptionStatus terminalStatus = corrected == PaymentStatus.EXPIRED
                ? SubscriptionStatus.EXPIRED : SubscriptionStatus.CANCELED;
        artisanSubscriptions.findWithLockById(payment.getSubscriptionId())
                .ifPresent(subscription -> deactivateCorrectedArtisan(subscription, terminalStatus));
        clientSubscriptions.findWithLockById(payment.getSubscriptionId())
                .ifPresent(subscription -> deactivateCorrectedClient(subscription, terminalStatus));
    }

    private void activateCorrectedArtisan(ArtisanSubscription subscription) {
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            LocalDateTime startsAt = LocalDateTime.now(clock);
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setStartsAt(startsAt);
            subscription.setExpiresAt(rules.expiryFrom(startsAt, subscription.getBillingPeriod()));
        }
        syncArtisanPremium(subscription);
    }

    private void activateCorrectedClient(ClientSubscription subscription) {
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            LocalDateTime startsAt = LocalDateTime.now(clock);
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setStartsAt(startsAt);
            subscription.setExpiresAt(rules.expiryFrom(startsAt, subscription.getBillingPeriod()));
        }
        syncClientPremium(subscription);
    }

    private void deactivateCorrectedArtisan(ArtisanSubscription subscription, SubscriptionStatus terminalStatus) {
        if (subscription.getStatus() == SubscriptionStatus.PENDING || subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            subscription.setStatus(terminalStatus);
        }
        syncArtisanPremium(subscription);
    }

    private void deactivateCorrectedClient(ClientSubscription subscription, SubscriptionStatus terminalStatus) {
        if (subscription.getStatus() == SubscriptionStatus.PENDING || subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            subscription.setStatus(terminalStatus);
        }
        syncClientPremium(subscription);
    }

    @Transactional
    public void correctSubscription(String subscriptionId, SubscriptionStateCorrectionRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        SubscriptionStatus corrected = request.getStatus();
        if (artisanSubscriptions.findWithLockById(subscriptionId).isPresent()) {
            ArtisanSubscription subscription = artisanSubscriptions.findWithLockById(subscriptionId).orElseThrow();
            SubscriptionStatus previous = subscription.getStatus();
            subscription.setStatus(corrected);
            if (corrected == SubscriptionStatus.ACTIVE && subscription.getStartsAt() == null) {
                LocalDateTime startsAt = LocalDateTime.now(clock);
                subscription.setStartsAt(startsAt);
                subscription.setExpiresAt(rules.expiryFrom(startsAt, subscription.getBillingPeriod()));
            }
            syncArtisanPremium(subscription);
            auditLogService.logFinancialState(AuditLogAction.Subscription.State.CORRECTED, actor, subscription.getAccount().getId(), FinancialAuditOperation.State.CORRECTION, previous, corrected, request.getReason(), null, subscriptionId);
            return;
        }
        ClientSubscription subscription = clientSubscriptions.findWithLockById(subscriptionId).orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
        SubscriptionStatus previous = subscription.getStatus();
        subscription.setStatus(corrected);
        if (corrected == SubscriptionStatus.ACTIVE && subscription.getStartsAt() == null) {
            LocalDateTime startsAt = LocalDateTime.now(clock);
            subscription.setStartsAt(startsAt);
            subscription.setExpiresAt(rules.expiryFrom(startsAt, subscription.getBillingPeriod()));
        }
        syncClientPremium(subscription);
            auditLogService.logFinancialState(AuditLogAction.Subscription.State.CORRECTED, actor, subscription.getAccount().getId(), FinancialAuditOperation.State.CORRECTION, previous, corrected, request.getReason(), null, subscriptionId);
    }

    @Transactional
    public void cancel(String subscriptionId, FinancialReasonRequest request) {
        User actor = currentUserProvider.requireCurrentUser();
        if (artisanSubscriptions.findWithLockById(subscriptionId).isPresent()) {
            ArtisanSubscription subscription = artisanSubscriptions.findWithLockById(subscriptionId).orElseThrow();
            SubscriptionStatus previous = subscription.getStatus();
            rules.requireTransition(subscription.getStatus(), SubscriptionStatus.CANCELED);
            subscription.setStatus(SubscriptionStatus.CANCELED);
            recordSubscriptionEvent(subscription.getAccount(), subscription.getId(), AnalyticsEvent.Subscription.CANCELED);
            cancelPendingPayments(subscriptionId);
            syncArtisanPremium(subscription);
            auditLogService.logFinancialState(AuditLogAction.Subscription.CANCELED, actor, subscription.getAccount().getId(), FinancialAuditOperation.Subscription.CANCEL, previous, SubscriptionStatus.CANCELED, request.getReason(), null, subscriptionId);
            return;
        }
        ClientSubscription subscription = clientSubscriptions.findWithLockById(subscriptionId).orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
        SubscriptionStatus previous = subscription.getStatus();
        rules.requireTransition(subscription.getStatus(), SubscriptionStatus.CANCELED);
        subscription.setStatus(SubscriptionStatus.CANCELED);
        recordSubscriptionEvent(subscription.getAccount(), subscription.getId(), AnalyticsEvent.Subscription.CANCELED);
        cancelPendingPayments(subscriptionId);
        syncClientPremium(subscription);
        auditLogService.logFinancialState(AuditLogAction.Subscription.CANCELED, actor, subscription.getAccount().getId(), FinancialAuditOperation.Subscription.CANCEL, previous, SubscriptionStatus.CANCELED, request.getReason(), null, subscriptionId);
    }

    private void syncArtisanPremium(ArtisanSubscription subscription) {
        if (subscription.getAccount().getAccountRole() == AccountRole.ARTISAN
                && subscription.getAccount().getArtisan() != null) {
            subscription.getAccount().getArtisan().setPremium(subscription.getStatus() == SubscriptionStatus.ACTIVE);
        }
    }

    private void syncClientPremium(ClientSubscription subscription) {
        if (subscription.getAccount().getAccountRole() == AccountRole.CLIENT
                && subscription.getAccount().getClient() != null) {
            subscription.getAccount().getClient().setPremium(subscription.getStatus() == SubscriptionStatus.ACTIVE);
        }
    }

    private void cancelPendingPayments(String subscriptionId) {
        payments.findBySubscriptionIdAndStatus(subscriptionId, PaymentStatus.PENDING)
                .forEach(payment -> payment.setStatus(PaymentStatus.CANCELED));
    }

    private void recordPaymentTransition(Payment payment, PaymentStatus previous, PaymentStatus current) {
        if (activityEventService != null && payment.getAccount() != null) {
            activityEventService.record(AnalyticsEvent.Payment.State.TRANSITION, payment.getAccount().getId(), payment.getId(),
                    Map.of(AnalyticsMetadata.Subscription.State.PREVIOUS, previous, AnalyticsMetadata.State.STATUS, current, AnalyticsMetadata.Subscription.Source.VALUE, AnalyticsEvent.Source.Admin.CORRECTION));
        }
    }

    private void recordSubscriptionEvent(User account, String subscriptionId, AnalyticsEvent.Subscription eventType) {
        if (activityEventService != null && account != null) {
            activityEventService.record(eventType, account.getId(), subscriptionId,
                    Map.of(AnalyticsMetadata.State.STATUS, eventType.status(), AnalyticsMetadata.Subscription.Source.VALUE, AnalyticsEvent.Source.Admin.ACTION));
        }
    }

    private void populate(ArtisanSubscription subscription, User target, SubscriptionPlan plan, String snapshot, LocalDateTime starts) {
        subscription.setAccount(target); subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setPlanId(plan.getId()); subscription.setPlanName(plan.getName()); subscription.setBillingPeriod(plan.getBillingPeriod()); subscription.setAmount(plan.getAmount()); subscription.setCurrency(plan.getCurrency()); subscription.setEntitlementsSnapshot(snapshot); subscription.setStartsAt(starts); subscription.setExpiresAt(rules.expiryFrom(starts, plan.getBillingPeriod()));
    }
    private void populate(ClientSubscription subscription, User target, SubscriptionPlan plan, String snapshot, LocalDateTime starts) {
        subscription.setAccount(target); subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setPlanId(plan.getId()); subscription.setPlanName(plan.getName()); subscription.setBillingPeriod(plan.getBillingPeriod()); subscription.setAmount(plan.getAmount()); subscription.setCurrency(plan.getCurrency()); subscription.setEntitlementsSnapshot(snapshot); subscription.setStartsAt(starts); subscription.setExpiresAt(rules.expiryFrom(starts, plan.getBillingPeriod()));
    }
    private String snapshot(SubscriptionPlan plan) {
        Map<String, String> entitlements = plan.getEntitlements().stream().collect(LinkedHashMap::new, (map, item) -> map.put(item.getKey(), item.getValue()), LinkedHashMap::putAll);
        try { return objectMapper.writeValueAsString(SubscriptionPlanSnapshot.builder().planId(plan.getId()).name(plan.getName()).subscriberType(plan.getSubscriberType()).billingPeriod(plan.getBillingPeriod()).amount(plan.getAmount()).currency(plan.getCurrency()).entitlements(entitlements).build()); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to snapshot plan", exception); }
    }
    private SubscriptionResponse toResponse(ArtisanSubscription value, SubscriberType type) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return SubscriptionResponse.builder().id(value.getId()).subscriberType(type).status(value.getStatus()).planName(value.getPlanName()).billingPeriod(value.getBillingPeriod()).amount(value.getAmount()).currency(value.getCurrency()).startsAt(value.getStartsAt()).expiresAt(value.getExpiresAt()).createdAt(value.getCreatedAt()).accountId(accountId).accountName(accountName).build();
    }
    private SubscriptionResponse toResponse(ClientSubscription value, SubscriberType type) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return SubscriptionResponse.builder().id(value.getId()).subscriberType(type).status(value.getStatus()).planName(value.getPlanName()).billingPeriod(value.getBillingPeriod()).amount(value.getAmount()).currency(value.getCurrency()).startsAt(value.getStartsAt()).expiresAt(value.getExpiresAt()).createdAt(value.getCreatedAt()).accountId(accountId).accountName(accountName).build();
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> all(int limit, String query) {
        List<SubscriptionResponse> result = new ArrayList<>();
        artisanSubscriptions.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .forEach(value -> result.add(toResponse(value, SubscriberType.ARTISAN)));
        clientSubscriptions.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .forEach(value -> result.add(toResponse(value, SubscriberType.CLIENT)));
        String normalized = query == null ? "" : query.trim().toLowerCase();
        return result.stream().filter(value -> normalized.isBlank()
                        || value.getId().toLowerCase().contains(normalized)
                        || value.getPlanName().toLowerCase().contains(normalized)
                        || value.getStatus().value().toLowerCase().contains(normalized))
                .sorted(Comparator.comparing(SubscriptionResponse::getStartsAt, Comparator.nullsLast(Comparator.reverseOrder()))).limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPayments(int limit, String query) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Iterable<Payment> iterable = (query == null || query.isBlank())
                ? payments.findAll(pageable)
                : payments.findByIdContainingIgnoreCaseOrSubscriptionIdContainingIgnoreCaseOrProviderCheckoutIdContainingIgnoreCase(query, query, query, pageable);
        return StreamSupport.stream(iterable.spliterator(), false)
                .map(this::toPaymentResponse)
                .toList();
    }

    private PaymentResponse toPaymentResponse(Payment value) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return PaymentResponse.builder()
                .id(value.getId())
                .subscriptionId(value.getSubscriptionId())
                .provider(value.getProvider())
                .status(value.getStatus())
                .amount(value.getAmount())
                .currency(value.getCurrency())
                .checkoutUrl(value.getCheckoutUrl())
                .createdAt(value.getCreatedAt())
                .accountId(accountId)
                .accountName(accountName)
                .build();
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscriptionById(String subscriptionId) {
        return artisanSubscriptions.findById(subscriptionId)
                .map(value -> toResponse(value, SubscriberType.ARTISAN))
                .or(() -> clientSubscriptions.findById(subscriptionId)
                        .map(value -> toResponse(value, SubscriberType.CLIENT)))
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
    }
}
