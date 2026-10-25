package com.project.souklab.service.subscription;

import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dto.subscription.SubscriptionPlanRequest;
import com.project.souklab.dto.subscription.SubscriptionPlanResponse;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.SubscriptionPlanEntitlement;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.FinancialAuditOperation;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.user.CurrentUserProvider;
import com.project.souklab.dto.subscription.FinancialReasonRequest;
import com.project.souklab.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSubscriptionPlanService {
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionPlanRules rules;
    private final SubscriptionProperties subscriptionProperties;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> list() {
        return planRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanResponse get(String id) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        return toResponse(plan);
    }

    @Transactional
    public SubscriptionPlanResponse create(SubscriptionPlanRequest request) {
        SubscriptionPlan plan = new SubscriptionPlan();
        apply(plan, request);
        SubscriptionPlan saved = planRepository.save(plan);
        auditLogService.logFinancialAction(AuditLogAction.Subscription.Plan.CREATED, currentUserProvider.requireCurrentUser(), null,
                FinancialAuditOperation.Plan.CREATE, saved.getId(), "NONE", saved.getName(), request.getReason(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SubscriptionPlanResponse update(String id, SubscriptionPlanRequest request) {
        SubscriptionPlan plan = planRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Subscription plan not found"));
        String previous = plan.getName() + ":" + plan.getAmount() + ":" + plan.getCurrency();
        apply(plan, request);
        auditLogService.logFinancialAction(AuditLogAction.Subscription.Plan.UPDATED, currentUserProvider.requireCurrentUser(), null,
                FinancialAuditOperation.Plan.UPDATE, id, previous, plan.getName() + ":" + plan.getAmount() + ":" + plan.getCurrency(), request.getReason(), null, null);
        return toResponse(plan);
    }

    @Transactional
    public void deactivate(String id, FinancialReasonRequest request) {
        SubscriptionPlan plan = planRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Subscription plan not found"));
        String previous = Boolean.toString(plan.isActive());
        plan.setActive(false);
        auditLogService.logFinancialAction(AuditLogAction.Subscription.Plan.DEACTIVATED, currentUserProvider.requireCurrentUser(), null,
                FinancialAuditOperation.Plan.DEACTIVATE, id, previous, "false", request.getReason(), null, null);
    }

    private void apply(SubscriptionPlan plan, SubscriptionPlanRequest request) {
        rules.validateDzdAmount(request.getAmount());
        rules.validateCurrency(subscriptionProperties.getCurrency());
        plan.setSubscriberType(request.getSubscriberType());
        plan.setName(request.getName().trim());
        plan.setDescription(request.getDescription());
        plan.setBillingPeriod(request.getBillingPeriod());
        plan.setAmount(request.getAmount());
        plan.setCurrency(subscriptionProperties.getCurrency());
        plan.setActive(request.isActive());
        plan.clearEntitlements();
        if (request.getEntitlements() != null) {
            request.getEntitlements().forEach((key, value) -> {
                SubscriptionPlanEntitlement entitlement = new SubscriptionPlanEntitlement();
                entitlement.setPlan(plan);
                entitlement.setKey(key);
                entitlement.setValue(value);
                plan.addEntitlement(entitlement);
            });
        }
    }

    private SubscriptionPlanResponse toResponse(SubscriptionPlan plan) {
        return SubscriptionPlanResponse.builder().id(plan.getId()).subscriberType(plan.getSubscriberType()).name(plan.getName())
                .description(plan.getDescription()).billingPeriod(plan.getBillingPeriod()).amount(plan.getAmount()).currency(plan.getCurrency())
                .active(plan.isActive())
                .entitlements(plan.getEntitlements().stream().collect(LinkedHashMap::new,
                        (map, entitlement) -> map.put(entitlement.getKey(), entitlement.getValue()), LinkedHashMap::putAll)).build();
    }
}
