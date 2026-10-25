package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.relay")
@Data
public class RelayProperties {

    private String host;
    private int port;
    private String clientLogin;
    private String clientPasscode;
    private String systemLogin;
    private String systemPasscode;
}
