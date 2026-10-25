package com.project.souklab.controller.subscription;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.subscription.PaymentResponse;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.service.subscription.SubscriptionAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = SubscriptionAccountController.class)
class SubscriptionAccountControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionAccountService accountService;

    @Test
    void accountEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions/current")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/subscriptions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments/payment-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/subscriptions/subscription-1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsHistoryPaymentsAndCurrentSubscription() throws Exception {
        SubscriptionResponse current = SubscriptionResponse.builder().id("subscription-1").planName("Client Monthly").build();
        PaymentResponse payment = PaymentResponse.builder().id("payment-1").subscriptionId("subscription-1").build();
        when(accountService.current()).thenReturn(current);
        when(accountService.history()).thenReturn(List.of(current));
        when(accountService.payments()).thenReturn(List.of(payment));
        when(accountService.payment("payment-1")).thenReturn(payment);

        mockMvc.perform(get("/api/v1/subscriptions/current").with(client()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("subscription-1"));
        mockMvc.perform(get("/api/v1/subscriptions").with(client()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("subscription-1"));
        mockMvc.perform(get("/api/v1/payments").with(client()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("payment-1"));
        mockMvc.perform(get("/api/v1/payments/payment-1").with(client()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.subscriptionId").value("subscription-1"));
    }

    @Test
    void cancellationDelegatesToAuthenticatedAccountService() throws Exception {
        mockMvc.perform(post("/api/v1/subscriptions/subscription-1/cancel").with(client()))
                .andExpect(status().isOk());
        verify(accountService).cancel(eq("subscription-1"));
    }
}
