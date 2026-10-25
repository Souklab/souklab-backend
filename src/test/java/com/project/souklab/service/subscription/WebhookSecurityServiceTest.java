package com.project.souklab.service.subscription;

import com.project.souklab.config.ChargilyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSecurityServiceTest {
    private WebhookSecurityService securityService;

    @BeforeEach
    void setUp() {
        ChargilyProperties properties = new ChargilyProperties();
        properties.setSecretKey("test-secret");
        properties.setWebhookEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        securityService = new WebhookSecurityService(properties);
    }

    @Test
    void verifiesHmacAndRoundTripsAesGcmPayload() throws Exception {
        byte[] body = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = toHex(mac.doFinal(body));
        assertThat(securityService.isValidSignature(body, signature)).isTrue();
        assertThat(securityService.isValidSignature(body, "00".repeat(32))).isFalse();

        String encrypted = securityService.encrypt(new String(body, StandardCharsets.UTF_8));
        assertThat(securityService.decrypt(encrypted)).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
