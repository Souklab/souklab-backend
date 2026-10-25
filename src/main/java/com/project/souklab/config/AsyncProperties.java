package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime configuration for application-managed asynchronous executors.
 */
@Configuration
@ConfigurationProperties(prefix = "app.async")
@Data
public class AsyncProperties {

    private Executor application = new Executor();
    private Executor workflow = new Executor();

    @Data
    public static class Executor {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private String threadNamePrefix;
    }
}
