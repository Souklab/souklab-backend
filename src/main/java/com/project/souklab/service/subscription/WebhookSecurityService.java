package com.project.souklab.service.subscription;

import com.project.souklab.config.ChargilyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Handles the two cryptographic operations required by the payment boundary. */
@Service
@RequiredArgsConstructor
public class WebhookSecurityService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final ChargilyProperties chargilyProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public boolean isValidSignature(byte[] rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }
        String normalized = signature.startsWith("sha256=") ? signature.substring("sha256=".length()) : signature;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(chargilyProperties.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] expected = mac.doFinal(rawBody);
            byte[] supplied = hexToBytes(normalized);
            return supplied.length == expected.length && MessageDigest.isEqual(expected, supplied);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public String encrypt(String rawPayload) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            int totalLength = Math.addExact(iv.length, ciphertext.length);
            byte[] result = Arrays.copyOf(iv, totalLength);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt webhook payload", exception);
        }
    }

    public String decrypt(String encryptedPayload) {
        try {
            byte[] encoded = Base64.getDecoder().decode(encryptedPayload);
            if (encoded.length <= IV_LENGTH) {
                throw new IllegalArgumentException("Encrypted payload is too short");
            }
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOf(encoded, IV_LENGTH)));
            return new String(cipher.doFinal(Arrays.copyOfRange(encoded, IV_LENGTH, encoded.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt webhook payload", exception);
        }
    }

    private SecretKeySpec encryptionKey() {
        byte[] key = Base64.getDecoder().decode(chargilyProperties.getWebhookEncryptionKey());
        return new SecretKeySpec(key, "AES");
    }

    private byte[] hexToBytes(String value) {
        if ((value.length() & 1) == 1) {
            throw new IllegalArgumentException("Odd signature length");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            int high = Character.digit(value.charAt(index), 16);
            int low = Character.digit(value.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Signature is not hexadecimal");
            }
            result[index / 2] = (byte) ((high << 4) + low);
        }
        return result;
    }
}
