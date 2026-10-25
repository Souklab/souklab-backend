package com.project.souklab.service.security;

import com.project.souklab.config.JwtProperties;
import com.project.souklab.dao.RefreshTokenRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.RefreshToken;
import com.project.souklab.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying RefreshTokenService token lifecycle management including
 * issuance, verification, rotation, revocation, and deterministic expiration handling.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T10:15:30.00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private static final long REFRESH_TOKEN_EXPIRATION_MS = 604800000L;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    private JwtProperties jwtProperties;
    private Clock fixedClock;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_INSTANT, ZONE_ID);

        jwtProperties = new JwtProperties();
        jwtProperties.setRefreshTokenExpirationMs(REFRESH_TOKEN_EXPIRATION_MS);

        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository,
                userRepository,
                jwtProperties,
                fixedClock
        );
    }

    /**
     * Verifies that createRefreshToken finds the user by ID and delegates to create a refresh token.
     */
    @Test
    @DisplayName("createRefreshToken with existing user creates and returns new token")
    void createRefreshToken_whenUserExists_shouldCreateAndReturnToken() {
        User user = User.builder().email("user@example.com").build();
        user.setId("user-123");

        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByUser(user)).thenReturn(Optional.empty());
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshToken result = refreshTokenService.createRefreshToken("user-123");

        assertThat(result).isNotNull();
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getToken()).isNotBlank();
        assertThat(UUID.fromString(result.getToken())).isNotNull();
        assertThat(result.getExpiryDate()).isEqualTo(FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS));

        verify(userRepository).findById("user-123");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    /**
     * Verifies that createRefreshToken throws ResourceNotFoundException when the user ID is unknown.
     */
    @Test
    @DisplayName("createRefreshToken with non-existent user throws ResourceNotFoundException")
    void createRefreshToken_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("unknown-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.createRefreshToken("unknown-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: unknown-id");

        verify(userRepository).findById("unknown-id");
        verify(refreshTokenRepository, never()).save(any());
    }

    /**
     * Verifies that createRefreshTokenForUser builds and saves a new token when user has no existing token.
     */
    @Test
    @DisplayName("createRefreshTokenForUser creates brand new token when no prior token exists")
    void createRefreshTokenForUser_whenNoExistingToken_shouldCreateNewToken() {
        User user = User.builder().email("fresh@example.com").build();
        user.setId("user-fresh");

        when(refreshTokenRepository.findByUser(user)).thenReturn(Optional.empty());
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshToken result = refreshTokenService.createRefreshTokenForUser(user);

        assertThat(result).isNotNull();
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getToken()).isNotBlank();
        assertThat(UUID.fromString(result.getToken())).isNotNull();
        assertThat(result.getExpiryDate()).isEqualTo(FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS));

        verify(refreshTokenRepository).findByUser(user);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    /**
     * Verifies that createRefreshTokenForUser mutates and updates existing token entity when already present.
     */
    @Test
    @DisplayName("createRefreshTokenForUser updates existing token entity with fresh UUID and expiry")
    void createRefreshTokenForUser_whenExistingTokenExists_shouldUpdateExistingToken() {
        User user = User.builder().email("existing@example.com").build();
        user.setId("user-existing");

        RefreshToken existing = RefreshToken.builder()
                .user(user)
                .token("old-uuid-token")
                .expiryDate(FIXED_INSTANT.minusSeconds(100))
                .build();

        when(refreshTokenRepository.findByUser(user)).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.save(existing)).thenReturn(existing);

        RefreshToken result = refreshTokenService.createRefreshTokenForUser(user);

        assertThat(result).isSameAs(existing);
        assertThat(result.getToken()).isNotEqualTo("old-uuid-token");
        assertThat(UUID.fromString(result.getToken())).isNotNull();
        assertThat(result.getExpiryDate()).isEqualTo(FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS));

        verify(refreshTokenRepository).findByUser(user);
        verify(refreshTokenRepository).save(existing);
    }

    /**
     * Verifies that rotateRefreshToken verifies old token, deletes it, flushes persistence, and saves a new token.
     */
    @Test
    @DisplayName("rotateRefreshToken deletes old token, flushes repo, and persists new token")
    void rotateRefreshToken_whenTokenValid_shouldDeleteOldFlushAndReturnNewToken() {
        User user = User.builder().email("rotate@example.com").build();
        user.setId("user-rotate");

        RefreshToken oldToken = RefreshToken.builder()
                .user(user)
                .token("old-rotation-token")
                .expiryDate(FIXED_INSTANT.plusSeconds(3600))
                .build();

        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshToken newToken = refreshTokenService.rotateRefreshToken(oldToken);

        InOrder inOrder = inOrder(refreshTokenRepository);
        inOrder.verify(refreshTokenRepository).delete(oldToken);
        inOrder.verify(refreshTokenRepository).flush();
        inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));

        assertThat(newToken).isNotNull();
        assertThat(newToken.getUser()).isEqualTo(user);
        assertThat(newToken.getToken()).isNotEqualTo("old-rotation-token");
        assertThat(UUID.fromString(newToken.getToken())).isNotNull();
        assertThat(newToken.getExpiryDate()).isEqualTo(FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS));
    }

    /**
     * Verifies that rotateRefreshToken deletes expired token and throws UnauthorizedException without saving new token.
     */
    @Test
    @DisplayName("rotateRefreshToken on expired token deletes token and throws UnauthorizedException")
    void rotateRefreshToken_whenTokenExpired_shouldDeleteOldTokenAndThrowUnauthorizedException() {
        User user = User.builder().email("expired@example.com").build();
        RefreshToken expiredToken = RefreshToken.builder()
                .user(user)
                .token("expired-token")
                .expiryDate(FIXED_INSTANT.minusMillis(1))
                .build();

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(expiredToken))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Refresh token has expired. Please sign in again.");

        verify(refreshTokenRepository).delete(expiredToken);
        verify(refreshTokenRepository, never()).flush();
        verify(refreshTokenRepository, never()).save(any());
    }

    /**
     * Verifies that verifyExpiration returns unexpired token without deleting.
     */
    @Test
    @DisplayName("verifyExpiration with unexpired token returns token unchanged")
    void verifyExpiration_whenTokenNotExpired_shouldReturnToken() {
        RefreshToken validToken = RefreshToken.builder()
                .token("active-token")
                .expiryDate(FIXED_INSTANT.plusMillis(5000))
                .build();

        RefreshToken result = refreshTokenService.verifyExpiration(validToken);

        assertThat(result).isSameAs(validToken);
        verify(refreshTokenRepository, never()).delete(any());
    }

    /**
     * Verifies that verifyExpiration deletes expired token and throws UnauthorizedException.
     */
    @Test
    @DisplayName("verifyExpiration with expired token deletes token and throws UnauthorizedException")
    void verifyExpiration_whenTokenExpired_shouldDeleteAndThrowUnauthorizedException() {
        RefreshToken expiredToken = RefreshToken.builder()
                .token("stale-token")
                .expiryDate(FIXED_INSTANT.minusSeconds(10))
                .build();

        assertThatThrownBy(() -> refreshTokenService.verifyExpiration(expiredToken))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Refresh token has expired. Please sign in again.");

        verify(refreshTokenRepository).delete(expiredToken);
    }

    /**
     * Verifies that deleteByUserId finds the user and deletes all their refresh tokens.
     */
    @Test
    @DisplayName("deleteByUserId with valid user delegates deletion to repository")
    void deleteByUserId_whenUserExists_shouldDeleteTokensForUser() {
        User user = User.builder().email("user@example.com").build();
        user.setId("user-to-delete");

        when(userRepository.findById("user-to-delete")).thenReturn(Optional.of(user));

        refreshTokenService.deleteByUserId("user-to-delete");

        verify(userRepository).findById("user-to-delete");
        verify(refreshTokenRepository).deleteByUser(user);
    }

    /**
     * Verifies that deleteByUserId throws ResourceNotFoundException when user is not found.
     */
    @Test
    @DisplayName("deleteByUserId with non-existent user throws ResourceNotFoundException")
    void deleteByUserId_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("missing-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.deleteByUserId("missing-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: missing-id");

        verify(userRepository).findById("missing-id");
        verify(refreshTokenRepository, never()).deleteByUser(any());
    }

    /**
     * Verifies that deleteByUser delegates directly to repository.
     */
    @Test
    @DisplayName("deleteByUser delegates directly to repository deleteByUser")
    void deleteByUser_shouldDelegateToRepository() {
        User user = User.builder().email("direct@example.com").build();

        refreshTokenService.deleteByUser(user);

        verify(refreshTokenRepository).deleteByUser(user);
    }

    /**
     * Verifies that deleteByToken delegates directly to repository.
     */
    @Test
    @DisplayName("deleteByToken delegates directly to repository deleteByToken")
    void deleteByToken_shouldDelegateToRepository() {
        refreshTokenService.deleteByToken("token-to-remove");

        verify(refreshTokenRepository).deleteByToken("token-to-remove");
    }

    /**
     * Verifies that findByToken returns matching token when found.
     */
    @Test
    @DisplayName("findByToken returns populated Optional when token exists")
    void findByToken_whenFound_shouldReturnOptionalToken() {
        RefreshToken token = RefreshToken.builder().token("existing-token").build();
        when(refreshTokenRepository.findByToken("existing-token")).thenReturn(Optional.of(token));

        Optional<RefreshToken> result = refreshTokenService.findByToken("existing-token");

        assertThat(result).contains(token);
        verify(refreshTokenRepository).findByToken("existing-token");
    }

    /**
     * Verifies that findByToken returns empty Optional when token does not exist.
     */
    @Test
    @DisplayName("findByToken returns empty Optional when token does not exist")
    void findByToken_whenNotFound_shouldReturnEmptyOptional() {
        when(refreshTokenRepository.findByToken("non-existent-token")).thenReturn(Optional.empty());

        Optional<RefreshToken> result = refreshTokenService.findByToken("non-existent-token");

        assertThat(result).isEmpty();
        verify(refreshTokenRepository).findByToken("non-existent-token");
    }
}
