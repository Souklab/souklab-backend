package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Runtime configuration for the in-memory catalog cache.
 */
@Configuration
@ConfigurationProperties(prefix = "app.cache")
@Data
public class CacheProperties {

    private Duration expireAfterWrite;
    private long maximumSize;
}
