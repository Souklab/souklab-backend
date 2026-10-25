package com.project.souklab.service.security;

import com.project.souklab.config.JwtProperties;
import com.project.souklab.dao.RefreshTokenRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.RefreshToken;
import com.project.souklab.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Transactional
    public RefreshToken createRefreshToken(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        return internalCreateOrUpdateRefreshToken(user);
    }

    @Transactional
    public RefreshToken createRefreshTokenForUser(User user) {
        return internalCreateOrUpdateRefreshToken(user);
    }

    private RefreshToken internalCreateOrUpdateRefreshToken(User user) {
        RefreshToken refreshToken = refreshTokenRepository.findByUser(user)
                .orElse(RefreshToken.builder().user(user).build());

        refreshToken.setUser(user);
        refreshToken.setExpiryDate(Instant.now(clock).plusMillis(jwtProperties.getRefreshTokenExpirationMs()));
        refreshToken.setToken(UUID.randomUUID().toString());

        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public RefreshToken rotateRefreshToken(RefreshToken oldToken) {
        verifyExpiration(oldToken);
        User user = oldToken.getUser();
        refreshTokenRepository.delete(oldToken);
        refreshTokenRepository.flush();

        RefreshToken newToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now(clock).plusMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build();

        return refreshTokenRepository.save(newToken);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now(clock)) < 0) {
            refreshTokenRepository.delete(token);
            throw new UnauthorizedException("Refresh token has expired. Please sign in again.");
        }
        return token;
    }

    @Transactional
    public void deleteByUserId(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        refreshTokenRepository.deleteByUser(user);
    }

    @Transactional
    public void deleteByUser(User user) {
        refreshTokenRepository.deleteByUser(user);
    }

    @Transactional
    public void deleteByToken(String token) {
        refreshTokenRepository.deleteByToken(token);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }
}
