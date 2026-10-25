package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.mailersend")
@Data
public class MailerSendProperties {

    private String apiKey;
    private String apiUrl;
    private String senderEmail;
    private String senderName;
    private Duration connectionTimeout;
    private Duration readTimeout;
}
