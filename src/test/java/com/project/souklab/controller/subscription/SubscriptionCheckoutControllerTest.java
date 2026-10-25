package com.project.souklab.controller.subscription;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.dto.subscription.SubscriptionCheckoutResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.service.subscription.SubscriptionCheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = SubscriptionCheckoutController.class)
class SubscriptionCheckoutControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionCheckoutService checkoutService;

    @Test
    void checkoutRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .contentType(APPLICATION_JSON)
                        .content("{\"planId\":\"plan-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutRequiresIdempotencyKey() throws Exception {
        when(checkoutService.checkout(any(), eq(null))).thenThrow(new BadRequestException("Idempotency-Key is required"));

        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .with(client())
                        .contentType(APPLICATION_JSON)
                        .content("{\"planId\":\"plan-1\"}"))
                .andExpect(status().isBadRequest());
        verify(checkoutService).checkout(any(), eq(null));
    }

    @Test
    void checkoutReturnsSanitizedIdentifiers() throws Exception {
        when(checkoutService.checkout(any(), eq("key-1"))).thenReturn(SubscriptionCheckoutResponse.builder()
                .paymentId("payment-1").subscriptionId("subscription-1").providerCheckoutId("checkout-1")
                .checkoutUrl("https://pay.example/checkout").build());

        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .with(client())
                        .header("Idempotency-Key", "key-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"planId\":\"plan-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value("payment-1"))
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://pay.example/checkout"));
    }

    @Test
    void checkoutReturnsSpecificErrorCodeWhenSubscriptionAlreadyPending() throws Exception {
        when(checkoutService.checkout(any(), eq("key-1"))).thenThrow(
                new BadRequestException(ApiErrorCode.SUBSCRIPTION_ALREADY_PENDING,
                        "A subscription is already active or a payment is already pending"));

        mockMvc.perform(post("/api/v1/subscriptions/checkout")
                        .with(client())
                        .header("Idempotency-Key", "key-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"planId\":\"plan-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SUBSCRIPTION_ALREADY_PENDING"))
                .andExpect(jsonPath("$.message").value("A subscription is already active or a payment is already pending"));
    }
}
