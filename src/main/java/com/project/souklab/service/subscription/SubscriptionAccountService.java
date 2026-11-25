package com.project.souklab.service.subscription;

import com.project.souklab.model.AccountRole;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dto.subscription.PaymentResponse;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.Payment;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.user.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionAccountService {
    private final CurrentUserProvider currentUserProvider;
    private final ArtisanSubscriptionRepository artisanSubscriptions;
    private final ClientSubscriptionRepository clientSubscriptions;
    private final PaymentRepository payments;
    private final SubscriptionPlanRules rules;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Transactional(readOnly = true)
    public SubscriptionResponse current() {
        User user = currentUserProvider.requireCurrentUser();
        List<SubscriptionStatus> statuses = List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING);
        SubscriptionResponse artisan = artisanSubscriptions.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(user.getId(), statuses)
                .map(value -> toResponse(value)).orElse(null);
        return artisan != null ? artisan : clientSubscriptions.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(user.getId(), statuses)
                .map(value -> toResponse(value)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> history() {
        User user = currentUserProvider.requireCurrentUser();
        List<SubscriptionResponse> result = new ArrayList<>();
        artisanSubscriptions.findByAccountIdOrderByCreatedAtDesc(user.getId()).forEach(value -> result.add(toResponse(value)));
        clientSubscriptions.findByAccountIdOrderByCreatedAtDesc(user.getId()).forEach(value -> result.add(toResponse(value)));
        result.sort(Comparator.comparing(SubscriptionResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> payments() {
        User user = currentUserProvider.requireCurrentUser();
        return payments.findByAccountIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 100)).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse payment(String paymentId) {
        User user = currentUserProvider.requireCurrentUser();
        return payments.findByIdAndAccountId(paymentId, user.getId()).map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    @Transactional
    public void cancel(String subscriptionId) {
        User user = currentUserProvider.requireCurrentUser();
        if (artisanSubscriptions.findWithLockById(subscriptionId).isPresent()) {
            ArtisanSubscription subscription = artisanSubscriptions.findWithLockById(subscriptionId).orElseThrow();
            requireOwner(subscription.getAccount(), user);
            rules.requireTransition(subscription.getStatus(), SubscriptionStatus.CANCELED);
            subscription.setStatus(SubscriptionStatus.CANCELED);
            recordCancellation(user, subscription.getId());
            cancelPendingPayments(subscriptionId);
            if (artisanSubscriptions.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) == 0
                    && user.getAccountRole() == AccountRole.ARTISAN) user.getArtisan().setPremium(false);
            return;
        }
        ClientSubscription subscription = clientSubscriptions.findWithLockById(subscriptionId).orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
        requireOwner(subscription.getAccount(), user);
        rules.requireTransition(subscription.getStatus(), SubscriptionStatus.CANCELED);
        subscription.setStatus(SubscriptionStatus.CANCELED);
        recordCancellation(user, subscription.getId());
        cancelPendingPayments(subscriptionId);
        if (clientSubscriptions.countByAccountIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE) == 0
                && user.getAccountRole() == AccountRole.CLIENT) user.getClient().setPremium(false);
    }

    private void cancelPendingPayments(String subscriptionId) {
        payments.findBySubscriptionIdAndStatus(subscriptionId, PaymentStatus.PENDING)
                .forEach(payment -> payment.setStatus(PaymentStatus.CANCELED));
    }

    private void requireOwner(User owner, User current) {
        if (!owner.getId().equals(current.getId())) throw new ForbiddenException("Subscription does not belong to the authenticated account");
    }

    private void recordCancellation(User account, String subscriptionId) {
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Subscription.CANCELED, account.getId(), subscriptionId,
                    Map.of(AnalyticsMetadata.State.STATUS, SubscriptionStatus.CANCELED, AnalyticsMetadata.Subscription.Source.VALUE, AnalyticsEvent.Source.Account.ACTION));
        }
    }

    private SubscriptionResponse toResponse(ArtisanSubscription value) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return SubscriptionResponse.builder().id(value.getId()).subscriberType(SubscriberType.ARTISAN).status(value.getStatus()).planName(value.getPlanName()).billingPeriod(value.getBillingPeriod()).amount(value.getAmount()).currency(value.getCurrency()).startsAt(value.getStartsAt()).expiresAt(value.getExpiresAt()).createdAt(value.getCreatedAt()).accountId(accountId).accountName(accountName).build();
    }
    private SubscriptionResponse toResponse(ClientSubscription value) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return SubscriptionResponse.builder().id(value.getId()).subscriberType(SubscriberType.CLIENT).status(value.getStatus()).planName(value.getPlanName()).billingPeriod(value.getBillingPeriod()).amount(value.getAmount()).currency(value.getCurrency()).startsAt(value.getStartsAt()).expiresAt(value.getExpiresAt()).createdAt(value.getCreatedAt()).accountId(accountId).accountName(accountName).build();
    }
    private PaymentResponse toResponse(Payment value) {
        String accountId = value.getAccount() != null ? value.getAccount().getId() : null;
        String accountName = value.getAccount() != null ? value.getAccount().getPublicDisplayName() : null;
        return PaymentResponse.builder().id(value.getId()).subscriptionId(value.getSubscriptionId()).provider(value.getProvider()).status(value.getStatus()).amount(value.getAmount()).currency(value.getCurrency()).checkoutUrl(value.getCheckoutUrl()).createdAt(value.getCreatedAt()).accountId(accountId).accountName(accountName).build();
    }
}
