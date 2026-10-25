package com.project.souklab.service.security;

import com.project.souklab.config.AuthProperties;
import com.project.souklab.dao.VerificationTokenRepository;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.model.User;
import com.project.souklab.model.VerificationToken;
import com.project.souklab.model.VerificationTokenType;
import com.project.souklab.util.CodeGeneratorUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class VerificationTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerificationTokenService.class);
    private static final String ERROR_INVALID_OR_EXPIRED_CODE = "Invalid or expired code.";
    private static final String ERROR_MAX_ATTEMPTS_EXCEEDED = "Maximum attempts exceeded. Please request a new code.";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final VerificationTokenRepository verificationTokenRepository;
    private final AuthProperties authProperties;
    private final Clock clock;

    /**
     * Issues a new numeric verification code for the specified user and token type.
     * Any existing active tokens of the same type for this user are invalidated first.
     * The token is stored as a SHA-256 hex digest, and the raw code is returned for email delivery.
     *
     * @param user the user requesting the token
     * @param type the type of verification token (EMAIL_VERIFICATION or PASSWORD_RESET)
     * @return the raw numeric code to be delivered via email
     */
    @Transactional
    public String issueToken(User user, VerificationTokenType type) {
        LocalDateTime now = LocalDateTime.now(clock);

        verificationTokenRepository.invalidateActiveTokens(user, type, now);

        int codeLength = authProperties.getVerification().getCodeLength();
        int expirationMinutes = authProperties.getVerification().getExpirationMinutes();

        String rawCode = CodeGeneratorUtil.generateNumericCode(codeLength);
        String codeHash = hashToken(rawCode);

        VerificationToken token = VerificationToken.builder()
                .user(user)
                .type(type)
                .codeHash(codeHash)
                .expiresAt(now.plusMinutes(expirationMinutes))
                .attempts(0)
                .build();

        verificationTokenRepository.save(token);
        LOGGER.info("Issued {} token for user ID: {}", type, user.getId());

        return rawCode;
    }

    /**
     * Validates and consumes a submitted verification code.
     * Checks expiration, attempt lockout, and cryptographic hash match.
     * Failed attempts are recorded in an independent transaction so they persist even when exceptions are thrown.
     *
     * @param user the user whose token is being verified
     * @param type the token type expected
     * @param submittedCode the raw 6-digit code submitted by the client
     * @throws BadRequestException if the token is missing, expired, locked, or mismatched
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void validateAndConsume(User user, VerificationTokenType type, String submittedCode) {
        LocalDateTime now = LocalDateTime.now(clock);

        VerificationToken token = verificationTokenRepository.findActiveToken(user, type, now)
                .orElseThrow(() -> {
                    LOGGER.warn("Verification failed: no active unexpired token found for user {} and type {}", user.getId(), type);
                    return new BadRequestException(ERROR_INVALID_OR_EXPIRED_CODE);
                });

        int maxAttempts = authProperties.getVerification().getMaxAttempts();

        if (token.getAttempts() >= maxAttempts) {
            LOGGER.warn("Verification failed: token for user {} and type {} is locked (attempts: {})", user.getId(), type, token.getAttempts());
            throw new BadRequestException(ERROR_MAX_ATTEMPTS_EXCEEDED);
        }

        String submittedHash = hashToken(submittedCode);

        if (!MessageDigest.isEqual(token.getCodeHash().getBytes(StandardCharsets.UTF_8), submittedHash.getBytes(StandardCharsets.UTF_8))) {
            int newAttempts = token.getAttempts() + 1;
            token.setAttempts(newAttempts);
            verificationTokenRepository.save(token);

            if (newAttempts >= maxAttempts) {
                LOGGER.warn("Token for user {} and type {} locked after reaching max attempts ({})", user.getId(), type, newAttempts);
                throw new BadRequestException(ERROR_MAX_ATTEMPTS_EXCEEDED);
            }

            LOGGER.warn("Verification code mismatch for user {} (attempt {}/{})", user.getId(), newAttempts, maxAttempts);
            throw new BadRequestException(ERROR_INVALID_OR_EXPIRED_CODE);
        }

        token.setUsedAt(now);
        verificationTokenRepository.save(token);
        LOGGER.info("Successfully validated and consumed {} token for user ID: {}", type, user.getId());
    }

    /**
     * Hashes a verification code using SHA-256.
     *
     * @param code the raw plain-text code
     * @return hex-encoded SHA-256 digest
     */
    public String hashToken(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] encodedHash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException(HASH_ALGORITHM + " algorithm not available", e);
        }
    }
}
