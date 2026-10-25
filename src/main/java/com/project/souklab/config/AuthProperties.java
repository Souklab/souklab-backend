package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Authentication and security policy configuration bound to {@code app.auth.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.auth")
@Data
public class AuthProperties {

    private LockoutConfig lockout = new LockoutConfig();
    private VerificationConfig verification = new VerificationConfig();

    /**
     * Account lockout policy configuration bound to {@code app.auth.lockout.*}.
     */
    @Data
    public static class LockoutConfig {
        private int maxAttempts;
        private int durationMinutes;
    }

    /**
     * One-time verification token (OTP) policy configuration bound to {@code app.auth.verification.*}.
     */
    @Data
    public static class VerificationConfig {
        private int maxAttempts;
        private int expirationMinutes;
        private int codeLength;
    }
}
