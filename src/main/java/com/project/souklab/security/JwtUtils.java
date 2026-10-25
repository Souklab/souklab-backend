package com.project.souklab.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.project.souklab.config.JwtProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtUtils {

    private static final String ERROR_UNEXPECTED_PRINCIPAL_TYPE_PREFIX = "Expected principal of type UserDetails, but found: ";
    public static final int AUTHORIZATION_SCHEMA_VERSION = 2;

    private final JwtProperties jwtProperties;
    private final Clock clock;

    @PostConstruct
    void validateConfiguration() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret must contain at least 32 UTF-8 bytes");
        }
        if (jwtProperties.getAccessTokenExpirationMs() == null
                || jwtProperties.getAccessTokenExpirationMs() <= 0) {
            throw new IllegalStateException("app.jwt.access-token-expiration-ms must be positive");
        }
        if (jwtProperties.getRefreshTokenExpirationMs() == null
                || jwtProperties.getRefreshTokenExpirationMs() <= 0) {
            throw new IllegalStateException("app.jwt.refresh-token-expiration-ms must be positive");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof UserDetails userPrincipal)) {
            throw new IllegalArgumentException(ERROR_UNEXPECTED_PRINCIPAL_TYPE_PREFIX
                    + (authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getSimpleName() : "null"));
        }
        return generateTokenFromUsername(userPrincipal.getUsername(), jwtProperties.getAccessTokenExpirationMs());
    }

    public String generateAccessToken(String username) {
        return generateTokenFromUsername(username, jwtProperties.getAccessTokenExpirationMs());
    }

    public String generateRefreshToken(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof UserDetails userPrincipal)) {
            throw new IllegalArgumentException(ERROR_UNEXPECTED_PRINCIPAL_TYPE_PREFIX
                    + (authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getSimpleName() : "null"));
        }
        return generateTokenFromUsername(userPrincipal.getUsername(), jwtProperties.getRefreshTokenExpirationMs());
    }

    public String generateTokenFromUsername(String username, long expirationMs) {
        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString())
                .claim(JwtClaim.Authorization.VERSION.value(), AUTHORIZATION_SCHEMA_VERSION)
                .issuedAt(Date.from(Instant.now(clock)))
                .expiration(Date.from(Instant.now(clock).plusMillis(expirationMs)))
                .signWith(getSigningKey())
                .compact();
    }

    public String getUserNameFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateJwtToken(String authToken) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(authToken)
                    .getPayload();
            Object version = claims.get(JwtClaim.Authorization.VERSION.value());
            if (!(version instanceof Number number) || number.intValue() != AUTHORIZATION_SCHEMA_VERSION) {
                log.warn("Rejected JWT with obsolete authorization schema version");
                return false;
            }
            return true;
        } catch (SecurityException e) {
            log.warn("Invalid JWT signature");
        } catch (MalformedJwtException e) {
            log.warn("Invalid JWT token");
        } catch (ExpiredJwtException e) {
            log.debug("JWT token is expired");
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token is unsupported");
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims string is empty");
        }
        return false;
    }
}
