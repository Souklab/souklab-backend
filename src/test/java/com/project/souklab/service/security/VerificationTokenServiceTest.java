package com.project.souklab.service.security;

import com.project.souklab.config.AuthProperties;
import com.project.souklab.dao.VerificationTokenRepository;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.model.User;
import com.project.souklab.model.VerificationToken;
import com.project.souklab.model.VerificationTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying VerificationTokenService token issuance, SHA-256 hashing,
 * code expiration, max attempts lockout, and atomic invalidation.
 */
@ExtendWith(MockitoExtension.class)
class VerificationTokenServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T10:15:30.00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private static final int CODE_EXPIRATION_MINUTES = 15;

    @Mock
    private VerificationTokenRepository verificationTokenRepository;

    private Clock fixedClock;
    private VerificationTokenService verificationTokenService;
    private User testUser;
    private LocalDateTime fixedNow;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_INSTANT, ZONE_ID);
        fixedNow = LocalDateTime.now(fixedClock);
        AuthProperties properties = new AuthProperties();
        properties.getVerification().setMaxAttempts(5);
        properties.getVerification().setExpirationMinutes(CODE_EXPIRATION_MINUTES);
        properties.getVerification().setCodeLength(6);
        verificationTokenService = new VerificationTokenService(verificationTokenRepository, properties, fixedClock);

        testUser = User.builder()
                .email("artisan@example.com")
                .build();
        testUser.setId("user-uuid-123");
    }

    /**
     * Verifies that issueToken invalidates prior active tokens, generates a 6-digit numeric
     * code, persists the SHA-256 digest with a 15-minute expiration, and returns the raw code.
     */
    @Test
    @DisplayName("issueToken: invalidates previous tokens, persists SHA-256 hash, and returns raw code")
    void issueToken_generatesRawNumericCode_hashesWithSha256_andInvalidatesActiveTokens() {
        VerificationTokenType type = VerificationTokenType.EMAIL_VERIFICATION;

        String rawCode = verificationTokenService.issueToken(testUser, type);

        assertThat(rawCode)
                .isNotNull()
                .hasSize(6)
                .matches("\\d{6}");

        verify(verificationTokenRepository).invalidateActiveTokens(testUser, type, fixedNow);

        ArgumentCaptor<VerificationToken> tokenCaptor = ArgumentCaptor.forClass(VerificationToken.class);
        verify(verificationTokenRepository).save(tokenCaptor.capture());

        VerificationToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getUser()).isEqualTo(testUser);
        assertThat(savedToken.getType()).isEqualTo(type);
        assertThat(savedToken.getAttempts()).isZero();
        assertThat(savedToken.getExpiresAt()).isEqualTo(fixedNow.plusMinutes(CODE_EXPIRATION_MINUTES));
        assertThat(savedToken.getUsedAt()).isNull();

        assertThat(rawCode).isNotEqualTo(savedToken.getCodeHash());

        String expectedSha256 = computeIndependentSha256(rawCode);
        assertThat(savedToken.getCodeHash()).isEqualTo(expectedSha256);
        assertThat(savedToken.getCodeHash()).isEqualTo(verificationTokenService.hashToken(rawCode));
    }

    /**
     * Verifies that validateAndConsume throws BadRequestException when no active token exists.
     */
    @Test
    @DisplayName("validateAndConsume: throws BadRequestException when no active token is found")
    void validateAndConsume_whenNoActiveTokenFound_throwsBadRequestException() {
        VerificationTokenType type = VerificationTokenType.PASSWORD_RESET;
        when(verificationTokenRepository.findActiveToken(testUser, type, fixedNow))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationTokenService.validateAndConsume(testUser, type, "123456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired code.");

        verify(verificationTokenRepository, never()).save(any());
    }

    /**
     * Verifies that validateAndConsume throws lockout BadRequestException when the token is already locked.
     */
    @Test
    @DisplayName("validateAndConsume: throws lockout BadRequestException when attempts already reached 5")
    void validateAndConsume_whenTokenAlreadyLockedAtMaxAttempts_throwsBadRequestExceptionWithoutIncrementing() {
        VerificationTokenType type = VerificationTokenType.EMAIL_VERIFICATION;
        VerificationToken token = VerificationToken.builder()
                .user(testUser)
                .type(type)
                .codeHash(computeIndependentSha256("123456"))
                .attempts(5)
                .expiresAt(fixedNow.plusMinutes(10))
                .build();

        when(verificationTokenRepository.findActiveToken(testUser, type, fixedNow))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> verificationTokenService.validateAndConsume(testUser, type, "123456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Maximum attempts exceeded. Please request a new code.");

        assertThat(token.getAttempts()).isEqualTo(5);
        verify(verificationTokenRepository, never()).save(any());
    }

    /**
     * Verifies that validateAndConsume increments attempts and throws BadRequestException on code mismatch below 5.
     */
    @Test
    @DisplayName("validateAndConsume: increments attempts and throws BadRequestException on code mismatch below 5")
    void validateAndConsume_whenCodeMismatchBelowMaxAttempts_incrementsAttemptsAndThrowsBadRequestException() {
        VerificationTokenType type = VerificationTokenType.EMAIL_VERIFICATION;
        VerificationToken token = VerificationToken.builder()
                .user(testUser)
                .type(type)
                .codeHash(computeIndependentSha256("123456"))
                .attempts(2)
                .expiresAt(fixedNow.plusMinutes(10))
                .build();

        when(verificationTokenRepository.findActiveToken(testUser, type, fixedNow))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> verificationTokenService.validateAndConsume(testUser, type, "999999"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired code.");

        assertThat(token.getAttempts()).isEqualTo(3);
        verify(verificationTokenRepository).save(token);
    }

    /**
     * Verifies that validateAndConsume increments attempts from 4 to 5 and throws lockout BadRequestException.
     */
    @Test
    @DisplayName("validateAndConsume: increments attempts to 5 and throws lockout BadRequestException on 5th mismatch")
    void validateAndConsume_whenCodeMismatchReachesMaxAttempts_incrementsToFiveAndThrowsLockoutException() {
        VerificationTokenType type = VerificationTokenType.EMAIL_VERIFICATION;
        VerificationToken token = VerificationToken.builder()
                .user(testUser)
                .type(type)
                .codeHash(computeIndependentSha256("123456"))
                .attempts(4)
                .expiresAt(fixedNow.plusMinutes(10))
                .build();

        when(verificationTokenRepository.findActiveToken(testUser, type, fixedNow))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> verificationTokenService.validateAndConsume(testUser, type, "999999"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Maximum attempts exceeded. Please request a new code.");

        ArgumentCaptor<VerificationToken> captor = ArgumentCaptor.forClass(VerificationToken.class);
        verify(verificationTokenRepository).save(captor.capture());
        VerificationToken saved = captor.getValue();
        assertThat(saved.getAttempts()).isEqualTo(5);
    }

    /**
     * Verifies that validateAndConsume sets usedAt and preserves attempts count upon successful match.
     */
    @Test
    @DisplayName("validateAndConsume: marks token used with clock instant and preserves attempts on successful match")
    void validateAndConsume_whenCodeMatches_marksTokenUsedAndDoesNotIncrementAttempts() {
        VerificationTokenType type = VerificationTokenType.EMAIL_VERIFICATION;
        String correctCode = "456789";
        VerificationToken token = VerificationToken.builder()
                .user(testUser)
                .type(type)
                .codeHash(computeIndependentSha256(correctCode))
                .attempts(1)
                .expiresAt(fixedNow.plusMinutes(10))
                .build();

        when(verificationTokenRepository.findActiveToken(testUser, type, fixedNow))
                .thenReturn(Optional.of(token));

        verificationTokenService.validateAndConsume(testUser, type, correctCode);

        ArgumentCaptor<VerificationToken> captor = ArgumentCaptor.forClass(VerificationToken.class);
        verify(verificationTokenRepository).save(captor.capture());
        VerificationToken saved = captor.getValue();
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getUsedAt()).isEqualTo(fixedNow);
    }

    /**
     * Verifies that hashToken produces a deterministic lowercase 64-character SHA-256 hex string
     * with leading zeros padded for bytes less than 0x10.
     */
    @Test
    @DisplayName("hashToken: produces deterministic 64-character lowercase hex digest with zero-padding")
    void hashToken_producesDeterministicHexDigestWithZeroPadding() {
        String testInput = "123456";
        String actualHash = verificationTokenService.hashToken(testInput);
        String expectedHash = computeIndependentSha256(testInput);

        assertThat(actualHash)
                .isEqualTo(expectedHash)
                .hasSize(64)
                .matches("^[0-9a-f]{64}$");
    }

    /**
     * Computes a SHA-256 hex digest independently to verify cryptographic correctness.
     *
     * @param input raw input string
     * @return 64-character hex encoded SHA-256 digest
     */
    private String computeIndependentSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedHash.length);
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
