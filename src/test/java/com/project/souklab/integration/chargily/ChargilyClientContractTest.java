package com.project.souklab.integration.chargily;

import com.project.souklab.config.ChargilyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargilyClientContractTest {
    @Test
    void sendsBearerAuthenticationAndConfiguredCheckoutEndpoint() {
        CapturingExchangeFunction exchange = new CapturingExchangeFunction(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"id\":\"checkout-1\",\"checkout_url\":\"https://pay.example/checkout-1\"}")
                .build());
        ChargilyProperties properties = properties();
        ChargilyClient client = new ChargilyClient(WebClient.builder().exchangeFunction(exchange), properties, new ObjectMapper());

        ChargilyCheckoutResponse response = client.createCheckout(ChargilyCheckoutRequest.builder()
                .amount(1500).currency("DZD").successUrl("https://app/success")
                .failureUrl("https://app/failure").webhookUrl("https://app/webhook")
                .locale("fr").feeAllocation("customer").metadata(Map.of("payment_id", "payment-1"))
                .build());

        assertThat(response.getId()).isEqualTo("checkout-1");
        assertThat(exchange.request().url().toString()).isEqualTo("https://pay.example/api/v2/checkouts");
        assertThat(exchange.request().headers().getFirst("Authorization")).isEqualTo("Bearer secret");
    }

    @Test
    void retriesRateLimitResponsesButNotValidationResponses() {
        ClientResponse success = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"id\":\"checkout-1\",\"checkout_url\":\"https://pay.example/checkout-1\"}")
                .build();
        SequencedExchangeFunction rateLimited = new SequencedExchangeFunction(List.of(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build(), success));
        ChargilyProperties properties = properties();
        properties.setRetryCount(1);
        ChargilyClient client = new ChargilyClient(WebClient.builder().exchangeFunction(rateLimited), properties, new ObjectMapper());

        client.createCheckout(request());
        assertThat(rateLimited.calls()).isEqualTo(2);

        SequencedExchangeFunction invalid = new SequencedExchangeFunction(List.of(
                ClientResponse.create(HttpStatus.BAD_REQUEST).build(), success));
        assertThatThrownBy(() -> new ChargilyClient(WebClient.builder().exchangeFunction(invalid), properties, new ObjectMapper())
                .createCheckout(request()))
                .isInstanceOf(ChargilyProviderException.class);
        assertThat(invalid.calls()).isEqualTo(1);
    }

    private ChargilyCheckoutRequest request() {
        return ChargilyCheckoutRequest.builder().amount(1500).currency("DZD")
                .successUrl("https://app/success").failureUrl("https://app/failure")
                .webhookUrl("https://app/webhook").locale("fr").feeAllocation("customer")
                .metadata(Map.of("payment_id", "payment-1")).build();
    }

    private ChargilyProperties properties() {
        ChargilyProperties chargily = new ChargilyProperties();
        chargily.setSecretKey("secret");
        chargily.setBaseUrl("https://pay.example/api/v2");
        chargily.setConnectTimeout(Duration.ofSeconds(1));
        chargily.setReadTimeout(Duration.ofSeconds(1));
        chargily.setResponseTimeout(Duration.ofSeconds(1));
        chargily.setRetryBackoff(Duration.ofMillis(1));
        chargily.setRetryCount(0);
        return chargily;
    }
}
