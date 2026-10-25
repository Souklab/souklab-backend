package com.project.souklab.integration.chargily;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.config.ChargilyProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ChargilyClient implements ChargilyCheckoutClient {
    private final WebClient.Builder webClientBuilder;
    private final ChargilyProperties chargilyProperties;
    private final ObjectMapper objectMapper;
    private final ChargilyResponseMapper responseMapper = new ChargilyResponseMapper();
    private final ChargilyErrorClassifier errorClassifier = new ChargilyErrorClassifier();

    @Override
    public ChargilyCheckoutResponse createCheckout(ChargilyCheckoutRequest request) {
        ChargilyProperties properties = chargilyProperties;
        for (int attempt = 0; attempt <= properties.getRetryCount(); attempt++) {
            try {
                String responseBody = client(properties).post()
                        .uri("/checkouts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getSecretKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ChargilyRequestMapper.from(request))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                if (responseBody == null || responseBody.isBlank()) {
                    throw new ChargilyProviderException("Chargily returned an empty response");
                }
                JsonNode body;
                try {
                    body = objectMapper.readTree(responseBody);
                } catch (JsonProcessingException exception) {
                    throw new ChargilyProviderException("Chargily returned an invalid response");
                }
                return responseMapper.toCheckoutResponse(body);
            } catch (WebClientResponseException exception) {
                if (!errorClassifier.isRetryable(exception.getStatusCode().value()) || attempt == properties.getRetryCount()) {
                    throw new ChargilyProviderException("Chargily request failed with status " + exception.getStatusCode().value());
                }
                backoff(properties.getRetryBackoff());
            } catch (WebClientRequestException exception) {
                if (attempt == properties.getRetryCount()) {
                    throw new ChargilyProviderException("Chargily request timed out or failed");
                }
                backoff(properties.getRetryBackoff());
            }
        }
        throw new ChargilyProviderException("Chargily checkout could not be created");
    }

    private WebClient client(ChargilyProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout())
                .doOnConnected(connection -> connection.addHandlerLast(new ReadTimeoutHandler(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)));
        return webClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private void backoff(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChargilyProviderException("Chargily retry interrupted");
        }
    }
}
