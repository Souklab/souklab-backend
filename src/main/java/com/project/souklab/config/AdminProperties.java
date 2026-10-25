package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.admin")
@Data
public class AdminProperties {

    private String defaultEmail;
    private String defaultPassword;
    private boolean bootstrapEnabled;
    private String defaultBanReason;
    private String defaultTimeoutReason;
}
