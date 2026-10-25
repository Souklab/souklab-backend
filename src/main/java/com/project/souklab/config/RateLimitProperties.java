package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Global API rate-limiting configuration bound to {@code app.rate-limit.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.rate-limit")
@Data
public class RateLimitProperties {

    private boolean enabled;
    private int capacity;
    private Duration refillDuration;
    private boolean userEnabled;
    private int userCapacity;
    private Duration userRefillDuration;
}
