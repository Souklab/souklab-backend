package com.project.souklab.integration.chargily;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.project.souklab.config.ChargilyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargilyWireMockContractTest {
    private WireMockServer server;
    private ChargilyProperties properties;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        properties = new ChargilyProperties();
        properties.setSecretKey("test-secret");
        properties.setBaseUrl(server.baseUrl());
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        properties.setResponseTimeout(Duration.ofSeconds(2));
        properties.setRetryBackoff(Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void sendsOfficialCheckoutPayloadAndMapsSanitizedResponse() {
        server.stubFor(post(urlEqualTo("/checkouts"))
                .withHeader("Authorization", equalTo("Bearer test-secret"))
                .withRequestBody(equalToJson("""
                        {
                          "amount": 1500,
                          "currency": "dzd",
                          "success_url": "https://app/success",
                          "failure_url": "https://app/failure",
                          "webhook_endpoint": "https://app/webhook",
                          "locale": "fr",
                          "chargily_pay_fees_allocation": "customer",
                          "metadata": {"payment_id": "payment-1", "subscription_id": "subscription-1"}
                        }
                        """))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"checkout-1\",\"checkout_url\":\"https://pay.example/checkout-1\",\"secret\":\"do-not-expose\"}")));

        ChargilyCheckoutResponse response = client(0).createCheckout(request());

        assertThat(response.getId()).isEqualTo("checkout-1");
        assertThat(response.getCheckoutUrl()).isEqualTo("https://pay.example/checkout-1");
        assertThat(response.toString()).doesNotContain("do-not-expose");
    }

    @Test
    void retries429ButDoesNotRetryUnauthorizedResponses() {
        server.stubFor(post(urlEqualTo("/checkouts"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("succeeded"));
        server.stubFor(post(urlEqualTo("/checkouts"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("succeeded")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"checkout-2\",\"checkout_url\":\"https://pay.example/checkout-2\"}")));

        assertThat(client(1).createCheckout(request()).getId()).isEqualTo("checkout-2");
        server.verify(2, postRequestedFor(urlEqualTo("/checkouts")));

        server.resetAll();
        server.stubFor(post(urlEqualTo("/checkouts")).willReturn(aResponse().withStatus(401).withBody("secret-error")));
        assertThatThrownBy(() -> client(2).createCheckout(request()))
                .isInstanceOf(ChargilyProviderException.class)
                .hasMessage("Chargily request failed with status 401");
        server.verify(1, postRequestedFor(urlEqualTo("/checkouts")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void doesNotRetryValidationAuthorizationOrNotFoundResponses(int status) {
        server.stubFor(post(urlEqualTo("/checkouts"))
                .willReturn(aResponse().withStatus(status).withBody("provider-sensitive-error")));

        assertThatThrownBy(() -> client(2).createCheckout(request()))
                .isInstanceOf(ChargilyProviderException.class)
                .hasMessage("Chargily request failed with status " + status);
        server.verify(1, postRequestedFor(urlEqualTo("/checkouts")));
    }

    @Test
    void retriesSelectedServerErrors() {
        server.stubFor(post(urlEqualTo("/checkouts"))
                .inScenario("server-error")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("succeeded"));
        server.stubFor(post(urlEqualTo("/checkouts"))
                .inScenario("server-error")
                .whenScenarioStateIs("succeeded")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"checkout-503\",\"checkout_url\":\"https://pay.example/checkout-503\"}")));

        assertThat(client(1).createCheckout(request()).getId()).isEqualTo("checkout-503");
        server.verify(2, postRequestedFor(urlEqualTo("/checkouts")));
    }

    @Test
    void convertsProviderTimeoutToSanitizedException() {
        properties.setResponseTimeout(Duration.ofMillis(50));
        properties.setReadTimeout(Duration.ofMillis(50));
        server.stubFor(post(urlEqualTo("/checkouts"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(250)
                        .withBody("{\"id\":\"late\",\"checkout_url\":\"https://pay.example/late\"}")));

        assertThatThrownBy(() -> client(0).createCheckout(request()))
                .isInstanceOf(ChargilyProviderException.class)
                .hasMessage("Chargily request timed out or failed");
    }

    private ChargilyClient client(int retryCount) {
        properties.setRetryCount(retryCount);
        return new ChargilyClient(WebClient.builder(), properties, new ObjectMapper());
    }

    private ChargilyCheckoutRequest request() {
        return ChargilyCheckoutRequest.builder().amount(1500).currency("DZD")
                .successUrl("https://app/success").failureUrl("https://app/failure")
                .webhookUrl("https://app/webhook").locale("fr").feeAllocation("customer")
                .metadata(Map.of("payment_id", "payment-1", "subscription_id", "subscription-1")).build();
    }
}
