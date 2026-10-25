package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.chargily")
@Data
public class ChargilyProperties {
    private String apiKey;
    private String secretKey;
    private String mode;
    private String baseUrl;
    private String webhookUrl;
    private String successUrl;
    private String failureUrl;
    private String locale;
    private String feeAllocation;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Duration responseTimeout;
    private int retryCount;
    private Duration retryBackoff;
    private int requestBodyLimit;
    private String webhookEncryptionKey;
    private boolean enabled;
    private String currency;
}
