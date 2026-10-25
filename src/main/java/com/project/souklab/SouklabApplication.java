package com.project.souklab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.config.AnalyticsRabbitProperties;
import com.project.souklab.config.OpenApiProperties;
import com.project.souklab.config.AnalyticsRetentionProperties;
import com.project.souklab.config.RateLimitEndpointProperties;
import com.project.souklab.config.AnalyticsJobProperties;
import com.project.souklab.config.AnalyticsExportProperties;
import com.project.souklab.config.HealthProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AnalyticsProperties.class, AnalyticsJobProperties.class, AnalyticsExportProperties.class, AnalyticsRabbitProperties.class, OpenApiProperties.class, AnalyticsRetentionProperties.class, RateLimitEndpointProperties.class, HealthProperties.class})
public class SouklabApplication {

    public static void main(String[] args) {
        SpringApplication.run(SouklabApplication.class, args);
    }

}
