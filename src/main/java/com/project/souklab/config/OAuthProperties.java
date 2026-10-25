package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.oauth")
@Data
public class OAuthProperties {

    private Google google = new Google();
    private int intentCookieMaxAgeSeconds;

    @Data
    public static class Google {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String authorizedRedirectUri;
    }
}
