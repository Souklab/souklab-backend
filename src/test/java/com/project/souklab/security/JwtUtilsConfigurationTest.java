package com.project.souklab.security;

import com.project.souklab.config.JwtProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies fail-fast validation of JWT security configuration.
 */
class JwtUtilsConfigurationTest {

    @Test
    void rejectsShortSecrets() {
        JwtProperties properties = validProperties();
        properties.setSecret("too-short");

        assertThatThrownBy(() -> new JwtUtils(properties, Clock.systemUTC()).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 UTF-8 bytes");
    }

    @Test
    void rejectsNonPositiveExpiration() {
        JwtProperties properties = validProperties();
        properties.setAccessTokenExpirationMs(0L);

        assertThatThrownBy(() -> new JwtUtils(properties, Clock.systemUTC()).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-token-expiration-ms");
    }

    private JwtProperties validProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a-valid-secret-with-at-least-32-utf8-bytes");
        properties.setAccessTokenExpirationMs(3_600_000L);
        properties.setRefreshTokenExpirationMs(86_400_000L);
        return properties;
    }
}
