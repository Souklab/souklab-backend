package com.project.souklab.security;

import com.project.souklab.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying JWT generation, claim parsing, signature validation,
 * deterministic timestamp assignment, and error branch handling in JwtUtils.
 */
@ExtendWith(MockitoExtension.class)
class JwtUtilsTest {

    private static final String TEST_SECRET = "testSecretKeyWithMinimumLengthOfThirtyTwoBytes256BitsRequired!";
    private static final long ACCESS_TOKEN_EXPIRATION_MS = 3600000L;
    private static final long REFRESH_TOKEN_EXPIRATION_MS = 86400000L;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T10:15:30.00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("UTC");

    private JwtUtils jwtUtils;
    private JwtProperties jwtProperties;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_INSTANT, ZONE_ID);

        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TEST_SECRET);
        jwtProperties.setAccessTokenExpirationMs(ACCESS_TOKEN_EXPIRATION_MS);
        jwtProperties.setRefreshTokenExpirationMs(REFRESH_TOKEN_EXPIRATION_MS);

        jwtUtils = new JwtUtils(jwtProperties, fixedClock);
    }

    /**
     * Verifies that generateAccessToken creates a valid signed JWT for a valid UserDetails principal.
     */
    @Test
    @DisplayName("generateAccessToken with UserDetails principal creates valid token with expected subject and expiration")
    void generateAccessToken_withUserDetailsPrincipal_shouldCreateValidToken() {
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("artisan@example.com");

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(userDetails);

        String token = jwtUtils.generateAccessToken(authentication);

        assertThat(token).isNotBlank();
        assertThat(jwtUtils.validateJwtToken(token)).isTrue();
        assertThat(jwtUtils.getUserNameFromJwtToken(token)).isEqualTo("artisan@example.com");

        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
        Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(fixedClock.instant())).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("artisan@example.com");
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(FIXED_INSTANT));
        assertThat(claims.getExpiration()).isEqualTo(Date.from(FIXED_INSTANT.plusMillis(ACCESS_TOKEN_EXPIRATION_MS)));
    }

    /**
     * Verifies that generateAccessToken throws IllegalArgumentException when principal is null.
     */
    @Test
    @DisplayName("generateAccessToken with null principal throws IllegalArgumentException")
    void generateAccessToken_withNullPrincipal_shouldThrowIllegalArgumentException() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> jwtUtils.generateAccessToken(authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected principal of type UserDetails, but found: null");
    }

    /**
     * Verifies that generateAccessToken creates a valid signed JWT when invoked directly with a username string.
     */
    @Test
    @DisplayName("generateAccessToken with username string creates valid token with expected subject and expiration")
    void generateAccessToken_withUsernameString_shouldCreateValidToken() {
        String username = "directuser@example.com";

        String token = jwtUtils.generateAccessToken(username);

        assertThat(token).isNotBlank();
        assertThat(jwtUtils.validateJwtToken(token)).isTrue();
        assertThat(jwtUtils.getUserNameFromJwtToken(token)).isEqualTo(username);

        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
        Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(fixedClock.instant())).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo(username);
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(FIXED_INSTANT));
        assertThat(claims.getExpiration()).isEqualTo(Date.from(FIXED_INSTANT.plusMillis(ACCESS_TOKEN_EXPIRATION_MS)));
    }

    /**
     * Verifies that generateRefreshToken creates a valid signed JWT for a valid UserDetails principal.
     */
    @Test
    @DisplayName("generateRefreshToken with UserDetails principal creates valid token with refresh expiration")
    void generateRefreshToken_withUserDetailsPrincipal_shouldCreateValidToken() {
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("refresh@example.com");

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(userDetails);

        String token = jwtUtils.generateRefreshToken(authentication);

        assertThat(token).isNotBlank();
        assertThat(jwtUtils.validateJwtToken(token)).isTrue();
        assertThat(jwtUtils.getUserNameFromJwtToken(token)).isEqualTo("refresh@example.com");

        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
        Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(fixedClock.instant())).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("refresh@example.com");
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(FIXED_INSTANT));
        assertThat(claims.getExpiration()).isEqualTo(Date.from(FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS)));
    }

    /**
     * Verifies that generateRefreshToken throws IllegalArgumentException when principal is null.
     */
    @Test
    @DisplayName("generateRefreshToken with null principal throws IllegalArgumentException")
    void generateRefreshToken_withNullPrincipal_shouldThrowIllegalArgumentException() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> jwtUtils.generateRefreshToken(authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected principal of type UserDetails, but found: null");
    }

    /**
     * Verifies round-trip claim extraction using getUserNameFromJwtToken.
     */
    @Test
    @DisplayName("getUserNameFromJwtToken extracts subject matching token owner")
    void getUserNameFromJwtToken_withValidToken_shouldReturnSubject() {
        String token = jwtUtils.generateTokenFromUsername("claimsubject@example.com", 60000L);

        String username = jwtUtils.getUserNameFromJwtToken(token);

        assertThat(username).isEqualTo("claimsubject@example.com");
    }

    /**
     * Verifies that validateJwtToken returns true for a newly issued valid token.
     */
    @Test
    @DisplayName("validateJwtToken returns true for valid token")
    void validateJwtToken_withValidToken_shouldReturnTrue() {
        String token = jwtUtils.generateAccessToken("valid@example.com");

        boolean isValid = jwtUtils.validateJwtToken(token);

        assertThat(isValid).isTrue();
    }

    /**
     * Verifies that validateJwtToken catches SecurityException and returns false when signature is invalid.
     */
    @Test
    @DisplayName("validateJwtToken returns false when token was signed with different key")
    void validateJwtToken_withDifferentKeySignature_shouldReturnFalse() {
        SecretKey foreignKey = Keys.hmacShaKeyFor("differentSecretKeyAlsoAtLeastThirtyTwoBytesLength!".getBytes());
        String forgedToken = Jwts.builder()
                .subject("attacker@example.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000L))
                .signWith(foreignKey)
                .compact();

        boolean isValid = jwtUtils.validateJwtToken(forgedToken);

        assertThat(isValid).isFalse();
    }

    /**
     * Verifies that validateJwtToken catches MalformedJwtException and returns false for malformed tokens.
     */
    @Test
    @DisplayName("validateJwtToken returns false for malformed token string")
    void validateJwtToken_withMalformedToken_shouldReturnFalse() {
        boolean isValid = jwtUtils.validateJwtToken("not.a.valid.jwt.structure");

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("validateJwtToken rejects tokens without the current authorization schema version")
    void validateJwtToken_withoutCurrentAuthorizationSchemaVersion_shouldReturnFalse() {
        String legacyToken = Jwts.builder()
                .subject("legacy@example.com")
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes()))
                .compact();

        assertThat(jwtUtils.validateJwtToken(legacyToken)).isFalse();
    }

    /**
     * Verifies that validateJwtToken catches ExpiredJwtException and returns false for expired tokens.
     */
    @Test
    @DisplayName("validateJwtToken returns false for expired token")
    void validateJwtToken_withExpiredToken_shouldReturnFalse() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
        String expiredToken = Jwts.builder()
                .subject("expired@example.com")
                .issuedAt(Date.from(fixedClock.instant().minusSeconds(120)))
                .expiration(Date.from(fixedClock.instant().minusSeconds(60)))
                .signWith(key)
                .compact();

        boolean isValid = jwtUtils.validateJwtToken(expiredToken);

        assertThat(isValid).isFalse();
    }

    /**
     * Verifies that validateJwtToken catches UnsupportedJwtException and returns false for unsigned tokens.
     */
    @Test
    @DisplayName("validateJwtToken returns false for unsigned token with no signature")
    void validateJwtToken_withUnsignedToken_shouldReturnFalse() {
        String unsignedToken = Jwts.builder()
                .subject("unsigned@example.com")
                .compact();

        boolean isValid = jwtUtils.validateJwtToken(unsignedToken);

        assertThat(isValid).isFalse();
    }

    /**
     * Verifies that validateJwtToken catches IllegalArgumentException and returns false for null, empty, or whitespace tokens.
     */
    @Test
    @DisplayName("validateJwtToken returns false for null, empty, or whitespace token")
    void validateJwtToken_withNullOrEmptyOrBlankToken_shouldReturnFalse() {
        assertThat(jwtUtils.validateJwtToken(null)).isFalse();
        assertThat(jwtUtils.validateJwtToken("")).isFalse();
        assertThat(jwtUtils.validateJwtToken("   ")).isFalse();
    }

    @Test
    void validateConfigurationRejectsMissingOrNonPositivePolicyValues() {
        JwtProperties invalidSecret = new JwtProperties();
        invalidSecret.setSecret("short");
        invalidSecret.setAccessTokenExpirationMs(1L);
        invalidSecret.setRefreshTokenExpirationMs(1L);
        assertThatThrownBy(() -> new JwtUtils(invalidSecret, fixedClock).validateConfiguration())
                .isInstanceOf(IllegalStateException.class);

        JwtProperties missingAccess = configuredProperties();
        missingAccess.setAccessTokenExpirationMs(null);
        assertThatThrownBy(() -> new JwtUtils(missingAccess, fixedClock).validateConfiguration())
                .hasMessageContaining("access-token");

        JwtProperties zeroAccess = configuredProperties();
        zeroAccess.setAccessTokenExpirationMs(0L);
        assertThatThrownBy(() -> new JwtUtils(zeroAccess, fixedClock).validateConfiguration())
                .hasMessageContaining("access-token");

        JwtProperties missingRefresh = configuredProperties();
        missingRefresh.setRefreshTokenExpirationMs(null);
        assertThatThrownBy(() -> new JwtUtils(missingRefresh, fixedClock).validateConfiguration())
                .hasMessageContaining("refresh-token");

        JwtProperties zeroRefresh = configuredProperties();
        zeroRefresh.setRefreshTokenExpirationMs(0L);
        assertThatThrownBy(() -> new JwtUtils(zeroRefresh, fixedClock).validateConfiguration())
                .hasMessageContaining("refresh-token");
    }

    @Test
    void rejectsTokenWithWrongAuthorizationSchemaNumber() {
        String token = Jwts.builder()
                .subject("wrong-version@example.com")
                .claim(JwtClaim.Authorization.VERSION.value(), 1)
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes()))
                .compact();
        assertThat(jwtUtils.validateJwtToken(token)).isFalse();
    }

    private JwtProperties configuredProperties() {
        JwtProperties configured = new JwtProperties();
        configured.setSecret(TEST_SECRET);
        configured.setAccessTokenExpirationMs(ACCESS_TOKEN_EXPIRATION_MS);
        configured.setRefreshTokenExpirationMs(REFRESH_TOKEN_EXPIRATION_MS);
        return configured;
    }
}
