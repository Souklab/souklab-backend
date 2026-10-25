package com.project.souklab.service.subscription;

import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dao.SubscriptionPlanRepository;
import com.project.souklab.dto.subscription.FinancialReasonRequest;
import com.project.souklab.dto.subscription.SubscriptionPlanRequest;
import com.project.souklab.dto.subscription.SubscriptionPlanResponse;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.BillingPeriod;
import com.project.souklab.model.CurrencyCode;
import com.project.souklab.model.FinancialAuditOperation;
import com.project.souklab.model.SubscriberType;
import com.project.souklab.model.SubscriptionPlan;
import com.project.souklab.model.User;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.user.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSubscriptionPlanServiceTest {

    @Mock
    private SubscriptionPlanRepository planRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private AuditLogService auditLogService;

    private SubscriptionProperties subscriptionProperties;
    private SubscriptionPlanRules rules;
    private AdminSubscriptionPlanService service;
    private User adminUser;

    @BeforeEach
    void setUp() {
        subscriptionProperties = new SubscriptionProperties();
        subscriptionProperties.setCurrency("DZD");
        subscriptionProperties.setMinimumPlanAmount(500);
        subscriptionProperties.setMaximumPlanAmount(50000);

        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        rules = new SubscriptionPlanRules(subscriptionProperties, clock);

        adminUser = new User();
        adminUser.setId("admin-1");

        service = new AdminSubscriptionPlanService(planRepository, rules, subscriptionProperties, currentUserProvider, auditLogService);
    }

    @Test
    void listReturnsAllMappedPlans() {
        SubscriptionPlan plan = samplePlan("plan-1", "Pro Artisan", 2000);
        when(planRepository.findAll()).thenReturn(List.of(plan));

        List<SubscriptionPlanResponse> responses = service.list();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo("plan-1");
        assertThat(responses.get(0).getName()).isEqualTo("Pro Artisan");
        assertThat(responses.get(0).getAmount()).isEqualTo(2000);
        assertThat(responses.get(0).getCurrency()).isEqualTo("DZD");
    }

    @Test
    void getReturnsPlanWhenFound() {
        SubscriptionPlan plan = samplePlan("plan-1", "Pro Artisan", 2000);
        when(planRepository.findById("plan-1")).thenReturn(Optional.of(plan));

        SubscriptionPlanResponse response = service.get("plan-1");

        assertThat(response.getId()).isEqualTo("plan-1");
        assertThat(response.getName()).isEqualTo("Pro Artisan");
    }

    @Test
    void getThrowsResourceNotFoundWhenMissing() {
        when(planRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Subscription plan not found");
    }

    @Test
    void createValidatesSavesAndAuditsPlan() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(adminUser);
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> {
            SubscriptionPlan p = inv.getArgument(0);
            p.setId("generated-plan-id");
            return p;
        });

        SubscriptionPlanRequest request = new SubscriptionPlanRequest();
        request.setSubscriberType(SubscriberType.ARTISAN);
        request.setName(" Artisan Premium ");
        request.setDescription("All premium features");
        request.setBillingPeriod(BillingPeriod.MONTHLY);
        request.setAmount(3500);
        request.setActive(true);
        request.setReason("Launch pricing");
        request.setEntitlements(Map.of("analytics", "full", "support", "priority"));

        SubscriptionPlanResponse response = service.create(request);

        assertThat(response.getId()).isEqualTo("generated-plan-id");
        assertThat(response.getName()).isEqualTo("Artisan Premium");
        assertThat(response.getAmount()).isEqualTo(3500);
        assertThat(response.getCurrency()).isEqualTo(CurrencyCode.DZD.value());
        assertThat(response.getEntitlements()).containsEntry("analytics", "full");

        verify(auditLogService).logFinancialAction(
                eq(AuditLogAction.Subscription.Plan.CREATED),
                eq(adminUser),
                eq(null),
                eq(FinancialAuditOperation.Plan.CREATE),
                eq("generated-plan-id"),
                eq("NONE"),
                eq("Artisan Premium"),
                eq("Launch pricing"),
                eq(null),
                eq(null)
        );
    }

    @Test
    void createRejectsInvalidAmountOutsideBounds() {
        SubscriptionPlanRequest request = new SubscriptionPlanRequest();
        request.setSubscriberType(SubscriberType.ARTISAN);
        request.setName("Too Cheap");
        request.setBillingPeriod(BillingPeriod.MONTHLY);
        request.setAmount(100); // Below 500 minimum

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounds");
    }

    @Test
    void updateModifiesPlanAndAuditsChange() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(adminUser);
        SubscriptionPlan existing = samplePlan("plan-1", "Standard", 1500);
        when(planRepository.findById("plan-1")).thenReturn(Optional.of(existing));

        SubscriptionPlanRequest request = new SubscriptionPlanRequest();
        request.setSubscriberType(SubscriberType.ARTISAN);
        request.setName("Updated Standard");
        request.setDescription("New desc");
        request.setBillingPeriod(BillingPeriod.MONTHLY);
        request.setAmount(2000);
        request.setActive(true);
        request.setReason("Inflation adjustment");

        SubscriptionPlanResponse response = service.update("plan-1", request);

        assertThat(response.getName()).isEqualTo("Updated Standard");
        assertThat(response.getAmount()).isEqualTo(2000);

        verify(auditLogService).logFinancialAction(
                eq(AuditLogAction.Subscription.Plan.UPDATED),
                eq(adminUser),
                eq(null),
                eq(FinancialAuditOperation.Plan.UPDATE),
                eq("plan-1"),
                eq("Standard:1500:DZD"),
                eq("Updated Standard:2000:DZD"),
                eq("Inflation adjustment"),
                eq(null),
                eq(null)
        );
    }

    @Test
    void updateThrowsWhenPlanNotFound() {
        when(planRepository.findById("missing")).thenReturn(Optional.empty());

        SubscriptionPlanRequest request = new SubscriptionPlanRequest();
        request.setName("Test");
        request.setAmount(1000);

        assertThatThrownBy(() -> service.update("missing", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Subscription plan not found");
    }

    @Test
    void deactivateSetsPlanInactiveAndAudits() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(adminUser);
        SubscriptionPlan plan = samplePlan("plan-1", "Old Plan", 1000);
        plan.setActive(true);
        when(planRepository.findById("plan-1")).thenReturn(Optional.of(plan));

        FinancialReasonRequest request = new FinancialReasonRequest();
        request.setReason("Discontinuing plan");

        service.deactivate("plan-1", request);

        assertThat(plan.isActive()).isFalse();
        verify(auditLogService).logFinancialAction(
                eq(AuditLogAction.Subscription.Plan.DEACTIVATED),
                eq(adminUser),
                eq(null),
                eq(FinancialAuditOperation.Plan.DEACTIVATE),
                eq("plan-1"),
                eq("true"),
                eq("false"),
                eq("Discontinuing plan"),
                eq(null),
                eq(null)
        );
    }

    @Test
    void deactivateThrowsWhenPlanNotFound() {
        when(planRepository.findById("missing")).thenReturn(Optional.empty());

        FinancialReasonRequest request = new FinancialReasonRequest();
        request.setReason("Test");

        assertThatThrownBy(() -> service.deactivate("missing", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SubscriptionPlan samplePlan(String id, String name, long amount) {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId(id);
        plan.setSubscriberType(SubscriberType.ARTISAN);
        plan.setName(name);
        plan.setDescription("Sample description");
        plan.setBillingPeriod(BillingPeriod.MONTHLY);
        plan.setAmount(amount);
        plan.setCurrency("DZD");
        plan.setActive(true);
        plan.setEntitlements(new ArrayList<>());
        return plan;
    }
}
