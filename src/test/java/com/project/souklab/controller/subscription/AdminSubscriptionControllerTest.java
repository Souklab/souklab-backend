package com.project.souklab.controller.subscription;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.PaymentWebhookLogRepository;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.service.subscription.AdminSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = AdminSubscriptionController.class)
class AdminSubscriptionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminSubscriptionService subscriptionService;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentWebhookLogRepository webhookLogRepository;

    @Test
    void financialOperationsRequireDedicatedPermission() throws Exception {
        String body = "{\"accountId\":\"account-1\",\"planId\":\"plan-1\",\"reason\":\"support grant\"}";
        mockMvc.perform(post("/api/v1/admin/subscriptions/grant").with(client())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void financialAdminCanGrantWithMandatoryReason() throws Exception {
        when(subscriptionService.grant(any())).thenReturn(SubscriptionResponse.builder()
                .id("subscription-1").build());

        mockMvc.perform(post("/api/v1/admin/subscriptions/grant").with(admin())
                        .contentType(APPLICATION_JSON)
                        .content("{\"accountId\":\"account-1\",\"planId\":\"plan-1\",\"reason\":\"support grant\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void grantRejectsMissingReasonBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/subscriptions/grant").with(admin())
                        .contentType(APPLICATION_JSON)
                        .content("{\"accountId\":\"account-1\",\"planId\":\"plan-1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getSubscriptionById_whenAdmin_returns200() throws Exception {
        when(subscriptionService.getSubscriptionById("sub-1")).thenReturn(SubscriptionResponse.builder()
                .id("sub-1").planName("Artisan Pro").build());

        mockMvc.perform(get("/api/v1/admin/subscriptions/sub-1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-1"))
                .andExpect(jsonPath("$.data.planName").value("Artisan Pro"));
    }

    @Test
    void getSubscriptionById_whenClient_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/subscriptions/sub-1").with(client()))
                .andExpect(status().isForbidden());
    }
}
