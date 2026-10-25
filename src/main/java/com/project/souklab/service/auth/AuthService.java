package com.project.souklab.service.auth;
import java.util.Map;
import com.project.souklab.util.LogSanitizer;

import com.project.souklab.service.profile.ProfileService;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.config.AuthProperties;
import com.project.souklab.config.JwtProperties;
import com.project.souklab.config.SupportProperties;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dao.OAuthIdentityRepository;
import com.project.souklab.dao.RefreshTokenRepository;
import com.project.souklab.dao.AuthorizationPermissionRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.auth.ChangePasswordRequestDTO;
import com.project.souklab.dto.auth.DeleteAccountRequest;
import com.project.souklab.dto.auth.ForgotPasswordRequestDTO;
import com.project.souklab.dto.auth.JwtResponseDTO;
import com.project.souklab.dto.auth.LoginDTO;
import com.project.souklab.dto.auth.ResendVerificationRequestDTO;
import com.project.souklab.dto.auth.ResetPasswordRequestDTO;
import com.project.souklab.dto.auth.TokenRefreshRequestDTO;
import com.project.souklab.dto.auth.TokenType;
import com.project.souklab.dto.auth.UserRegistrationDTO;
import com.project.souklab.dto.auth.VerifyEmailRequestDTO;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.exception.AppException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientType;
import com.project.souklab.model.OAuthIdentity;
import com.project.souklab.model.OAuthProvider;
import com.project.souklab.model.RefreshToken;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.security.Permission;
import com.project.souklab.model.User;
import com.project.souklab.model.VerificationTokenType;
import com.project.souklab.security.JwtUtils;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.profile.ProfileResponseMapper;
import com.project.souklab.service.security.RefreshTokenService;
import com.project.souklab.service.security.VerificationTokenService;
import com.project.souklab.util.EmailUtil;
import com.project.souklab.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authentication and identity service.
 * Owns credentials, token issuance/rotation, account lockout, email verification,
 * password management, and Google OAuth2 flow.
 * <p>
 * Profile lifecycle (getCurrentUser, completeProfile, patchCurrentUser) lives in
 * {@link ProfileService}.
 * Mapping logic lives in {@link ProfileResponseMapper}.
 */
@Service
@Slf4j
public class AuthService {

    private static final String ERROR_USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String ERROR_INVALID_CREDENTIALS = "Invalid email or password.";
    private static final String ERROR_PERMISSION_NOT_FOUND_PREFIX = "Permission not found: ";
    private static final long MS_PER_SECOND = 1000L;

    private final UserRepository userRepository;
    private final AuthorizationPermissionRepository permissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthIdentityRepository oauthIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private final SupportProperties supportProperties;
    private final VerificationTokenService verificationTokenService;
    private final EmailUtil emailUtil;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final ProfileResponseMapper profileResponseMapper;
    private final ClientRepository clientRepository;
    private final ActivityEventService activityEventService;

    @Autowired
    public AuthService(UserRepository userRepository,
                       ClientRepository clientRepository,
                       AuthorizationPermissionRepository permissionRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OAuthIdentityRepository oauthIdentityRepository,
                       PasswordEncoder passwordEncoder,
                       NotificationService notificationService,
                       JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       AuthProperties authProperties,
                       SupportProperties supportProperties,
                       VerificationTokenService verificationTokenService,
                       EmailUtil emailUtil,
                       AuditLogService auditLogService,
                       Clock clock,
                       ProfileResponseMapper profileResponseMapper,
                       ActivityEventService activityEventService) {
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.permissionRepository = permissionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.oauthIdentityRepository = oauthIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.supportProperties = supportProperties;
        this.verificationTokenService = verificationTokenService;
        this.emailUtil = emailUtil;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.profileResponseMapper = profileResponseMapper;
        this.activityEventService = activityEventService;
    }

    /** Backward-compatible constructor retained for focused service tests and external adapters. */
    public AuthService(UserRepository userRepository,
                       ClientRepository clientRepository,
                       AuthorizationPermissionRepository permissionRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OAuthIdentityRepository oauthIdentityRepository,
                       PasswordEncoder passwordEncoder,
                       NotificationService notificationService,
                       JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       AuthProperties authProperties,
                       SupportProperties supportProperties,
                       VerificationTokenService verificationTokenService,
                       EmailUtil emailUtil,
                       AuditLogService auditLogService,
                       Clock clock,
                       ProfileResponseMapper profileResponseMapper) {
        this(userRepository, clientRepository, permissionRepository, refreshTokenRepository, oauthIdentityRepository,
                passwordEncoder, notificationService, jwtUtils, refreshTokenService, jwtProperties, authProperties, supportProperties,
                verificationTokenService, emailUtil, auditLogService, clock, profileResponseMapper, null);
    }

    /** Legacy constructor without clientRepository retained for backwards compatibility. */
    public AuthService(UserRepository userRepository,
                       AuthorizationPermissionRepository permissionRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OAuthIdentityRepository oauthIdentityRepository,
                       PasswordEncoder passwordEncoder,
                       NotificationService notificationService,
                       JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       AuthProperties authProperties,
                       SupportProperties supportProperties,
                       VerificationTokenService verificationTokenService,
                       EmailUtil emailUtil,
                       AuditLogService auditLogService,
                       Clock clock,
                       ProfileResponseMapper profileResponseMapper) {
        this(userRepository, null, permissionRepository, refreshTokenRepository, oauthIdentityRepository,
                passwordEncoder, notificationService, jwtUtils, refreshTokenService, jwtProperties, authProperties, supportProperties,
                verificationTokenService, emailUtil, auditLogService, clock, profileResponseMapper, null);
    }

    /**
     * Registers a new user.
     * ARTISAN users start with status PENDING (requiring administrative review).
     * CLIENT users start with status ACTIVE (can immediately log in and participate).
     * Public registration strictly prohibits ADMIN accounts.
     *
     * @param dto the registration payload containing email, password, account type, and name fields
     * @return the profile response for the newly created user
     * @throws ConflictException         if the email is already registered
     * @throws BadRequestException       if the requested account type is invalid or ADMIN
     * @throws ResourceNotFoundException if a required permission does not exist in the database
     * @throws AppException              with status 503 if the verification email cannot be delivered;
     *                                   the registration transaction is rolled back in that case
     */
    @Transactional
    public ProfileResponse registerUser(UserRegistrationDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered: " + email);
        }

        AccountRole role = parseRegistrationRole(dto.getAccountType());
        Set<AuthorizationPermission> permissions = permissionsForRegistration(role == AccountRole.ARTISAN);
        AccountStatus initialStatus = role == AccountRole.ARTISAN ? AccountStatus.PENDING : AccountStatus.ACTIVE;

        User savedUser = userRepository.save(buildNewUser(dto, email, permissions, initialStatus));
        if (role == AccountRole.CLIENT && clientRepository != null) {
            Client client = Client.builder()
                    .user(savedUser)
                    .clientType(ClientType.INDIVIDUAL.value())
                    .build();
            clientRepository.save(client);
            savedUser.setClient(client);
        }
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Registration.CREATED, savedUser.getId(), savedUser.getId(),
                    Map.of(AnalyticsMetadata.Account.TYPE, role));
        }

        String rawCode = verificationTokenService.issueToken(savedUser, VerificationTokenType.EMAIL_VERIFICATION);
        try {
            emailUtil.sendVerificationCodeSynchronous(savedUser.getEmail(), rawCode);
        } catch (Exception e) {
            throw new AppException("Registration could not be completed because the verification email could not be sent. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE, e);
        }

        if (role == AccountRole.ARTISAN) {
            try {
                notificationService.notifyAdmins("New artisan registration pending approval: " + savedUser.getEmail());
            } catch (Exception e) {
                log.warn("Could not dispatch admin notification for registration: {}", e.getMessage());
            }
        }

        return profileResponseMapper.mapToProfileResponse(savedUser);
    }

    /** Handles logout input at the service boundary, including current-principal resolution. */
    @Transactional
    public void logout(TokenRefreshRequestDTO request) {
        String userEmail = SecurityUtils.getCurrentUsername();
        String refreshToken = request != null ? request.getRefreshToken() : null;
        logout(userEmail, refreshToken);
    }

    /**
     * Authenticates a user by email and password, issuing access + refresh token pair.
     * Enforces a 15-minute temporary lockout after 5 consecutive failed login attempts.
     *
     * @param dto     the login credentials (identifier + password)
     * @param request the incoming HTTP request, used to capture the client IP address
     * @return a JWT response containing access token, refresh token, and user summary
     * @throws BadRequestException   if the login identifier is blank
     * @throws UnauthorizedException if credentials are invalid or the account uses social login
     * @throws ForbiddenException    if the account is temporarily locked, suspended, or rejected
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, BadRequestException.class, ForbiddenException.class})
    public JwtResponseDTO login(LoginDTO dto, HttpServletRequest request) {
        String identifier = dto.getLoginIdentifier();
        if (identifier == null || identifier.isBlank()) {
            throw new BadRequestException("Email is required for login.");
        }

        String email = identifier.toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException(ERROR_INVALID_CREDENTIALS));

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new UnauthorizedException(ERROR_INVALID_CREDENTIALS);
        }

        assertAccountNotLocked(user);

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw recordFailedAttempt(user);
        }

        validateAccountStatus(user);

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now(clock));
        if (request != null) {
            user.setLastLoginIp(extractClientIp(request));
        }
        userRepository.save(user);
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Authentication.Login.SUCCEEDED, user.getId(), user.getId(), Map.of());
        }

        return generateJwtResponse(user);
    }

    /**
     * Rotates refresh tokens (revokes old, issues new pair).
     *
     * @param request the token refresh payload containing the current refresh token string
     * @return a new JWT response with rotated access and refresh tokens
     * @throws UnauthorizedException if the provided refresh token is not found or already revoked
     */
    @Transactional
    public JwtResponseDTO refreshToken(TokenRefreshRequestDTO request) {
        RefreshToken oldToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token."));

        RefreshToken newToken = refreshTokenService.rotateRefreshToken(oldToken);
        User user = newToken.getUser();
        if (user.getDeletedAt() != null || user.getStatus() == AccountStatus.DELETED) {
            throw new UnauthorizedException("Account has been deactivated.");
        }

        String accessToken = jwtUtils.generateAccessToken(user.getEmail());

        return JwtResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(newToken.getToken())
                .tokenType(TokenType.BEARER)
                .expiresIn(jwtProperties.getAccessTokenExpirationMs() / MS_PER_SECOND)
                .user(profileResponseMapper.mapToProfileResponse(user))
                .permissions(canonicalPermissions(user))
                .build();
    }

    /**
     * Revokes all refresh tokens for the given user.
     *
     * @param userEmail       the email address of the user whose tokens should be revoked, or {@code null}
     * @param refreshTokenStr a specific refresh token to revoke immediately, or {@code null}
     */
    @Transactional
    public void logout(String userEmail, String refreshTokenStr) {
        if (refreshTokenStr != null && !refreshTokenStr.isBlank()) {
            refreshTokenRepository.deleteByToken(refreshTokenStr.trim());
        }
        if (userEmail != null && !userEmail.isBlank()) {
            userRepository.findByEmail(userEmail.toLowerCase())
                    .ifPresent(refreshTokenService::deleteByUser);
        }
    }

    /** Permanently disables the account while retaining an anonymised audit-safe record. */
    @Transactional
    public void deleteCurrentAccount(DeleteAccountRequest request) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException("User is not authenticated.");
        }
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UnauthorizedException("User is not authenticated."));
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            verifyPasswordCredentials(user, request);
        } else {
            verifyOAuthSession(user, request);
        }

        refreshTokenService.deleteByUser(user);
        oauthIdentityRepository.findByUser(user).forEach(identity -> {
            identity.setProviderUserId("deleted-" + identity.getId());
            identity.setEmail(null);
            oauthIdentityRepository.save(identity);
        });
        String originalEmail = user.getEmail();
        user.setEmail("deleted+" + user.getId() + "@deleted.souklab.invalid");
        user.setPassword(null);
        user.setFirstName("Deleted");
        user.setLastName("User");
        user.setPhone(null);
        user.setAvatarUrl(null);
        user.setStatus(AccountStatus.DELETED);
        user.setDeletedAt(LocalDateTime.now(clock));
        userRepository.save(user);
        auditLogService.logAction(AuditLogAction.User.DELETED, "Account deleted and direct identifiers anonymised", originalEmail);
    }

    /**
     * Verifies a user's email address using a submitted 6-digit verification code.
     *
     * @param dto the verification payload containing email and the 6-digit code
     * @throws ResourceNotFoundException if no user exists with the provided email
     * @throws BadRequestException       if the verification code is invalid or expired
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void verifyEmail(VerifyEmailRequestDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Invalid or expired code."));

        verificationTokenService.validateAndConsume(user, VerificationTokenType.EMAIL_VERIFICATION, dto.getCode());

        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now(clock));
        userRepository.save(user);

        auditLogService.logAction(AuditLogAction.Authentication.Email.VERIFIED, "Email verified for user: " + user.getEmail(), user.getEmail());
    }

    /**
     * Resends an email verification code if the user exists and is not yet verified.
     * Always produces identical outward response behaviour to prevent account enumeration.
     *
     * @param dto the resend payload containing the user's email address
     */
    @Transactional
    public void resendVerification(ResendVerificationRequestDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                try {
                    String rawCode = verificationTokenService.issueToken(user, VerificationTokenType.EMAIL_VERIFICATION);
                    emailUtil.sendVerificationCode(user.getEmail(), rawCode);
                } catch (Exception e) {
                    log.warn("Failed to issue or send resend verification email for {}: {}", LogSanitizer.sanitize(email), e.getMessage());
                }
            }
        });
    }

    /**
     * Initiates the password reset flow.
     * If the user exists with a password, issues a reset token and emails the 6-digit code.
     * If the user exists without a password (OAuth-only), emails an informational notice.
     * Always returns a generic success response outward.
     *
     * @param dto the forgot-password payload containing the user's email address
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequestDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getDeletedAt() != null || user.getStatus() == AccountStatus.DELETED) {
                return;
            }
            if (user.getPassword() != null && !user.getPassword().isBlank()) {
                try {
                    String rawCode = verificationTokenService.issueToken(user, VerificationTokenType.PASSWORD_RESET);
                    emailUtil.sendPasswordResetCode(user.getEmail(), rawCode);
                } catch (Exception e) {
                    log.warn("Failed to issue or send password reset code for {}: {}", LogSanitizer.sanitize(email), e.getMessage());
                }
            } else {
                try {
                    emailUtil.sendOAuthOnlyPasswordResetNotice(user.getEmail());
                } catch (Exception e) {
                    log.warn("Failed to send OAuth password reset notice for {}: {}", LogSanitizer.sanitize(email), e.getMessage());
                }
            }
        });
    }

    /**
     * Resets a user's password using the submitted 6-digit code.
     * Invalidates all active refresh tokens for the user upon completion.
     *
     * @param dto the reset-password payload containing email, code, and new password
     * @throws BadRequestException if the code is invalid or expired
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPassword(ResetPasswordRequestDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Invalid or expired code."));

        if (user.getDeletedAt() != null || user.getStatus() == AccountStatus.DELETED) {
            throw new BadRequestException("Invalid or expired code.");
        }

        verificationTokenService.validateAndConsume(user, VerificationTokenType.PASSWORD_RESET, dto.getCode());

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(user);

        refreshTokenService.deleteByUser(user);

        auditLogService.logAction(AuditLogAction.Authentication.Password.Reset.COMPLETED, "Password reset completed for user: " + user.getEmail(), user.getEmail());
    }

    /**
     * Changes an authenticated user's password.
     * <ol>
     *   <li>Resolves current user from SecurityContext.</li>
     *   <li>Rejects OAuth-only users (no password to change).</li>
     *   <li>Verifies oldPassword matches current password.</li>
     *   <li>Validates newPassword is not identical to oldPassword.</li>
     *   <li>Encodes and saves new password.</li>
     *   <li>Deletes active refresh tokens to force re-authentication across devices.</li>
     *   <li>Sends password-changed email notification.</li>
     *   <li>Logs PASSWORD_CHANGED audit log entry on success.</li>
     * </ol>
     *
     * @param request the change-password payload containing old and new passwords
     * @throws UnauthorizedException     if no user is authenticated, or the old password is incorrect
     * @throws BadRequestException       if the account uses social login and has no password
     * @throws ResourceNotFoundException if the authenticated email does not map to an existing user
     */
    @Transactional
    public void changePassword(ChangePasswordRequestDTO request) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new UnauthorizedException("Not authenticated.");
        }

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + email));

        if (user.getDeletedAt() != null || user.getStatus() == AccountStatus.DELETED) {
            throw new UnauthorizedException("User is not authenticated.");
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BadRequestException("This account was created via social login and does not have a password to change. Please continue signing in with Google.");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new UnauthorizedException("Current password is incorrect.");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        refreshTokenService.deleteByUser(user);

        try {
            emailUtil.sendPasswordChangedNotice(user.getEmail());
        } catch (Exception e) {
            log.warn("Failed to send password changed notice email for {}: {}", user.getEmail(), e.getMessage());
        }

        auditLogService.logAction(AuditLogAction.Authentication.Password.Changed.VALUE, "Password changed for user: " + user.getEmail(), user.getEmail());
    }

    /**
     * Processes Google OAuth2 authentication callback:
     * <ol>
     *   <li>Matches existing OAuthIdentity (provider=GOOGLE, provider_user_id)</li>
     *   <li>Otherwise matches existing User by verified email and auto-links</li>
     *   <li>Otherwise creates new User + OAuthIdentity with requested role from intent</li>
     * </ol>
     *
     * @param oAuth2User the principal returned by the OAuth2 provider
     * @param intentRole the role string captured during OAuth2 registration intent (e.g., "ARTISAN")
     * @param request    the incoming HTTP request used to capture the client IP address
     * @return a JWT response for the authenticated or newly created user
     * @throws BadRequestException       if the provider did not return an email, or the intent role is invalid
     * @throws ResourceNotFoundException if the resolved role does not exist in the database
     */
    @Transactional
    public JwtResponseDTO processOAuth2Success(OAuth2User oAuth2User, String intentRole, HttpServletRequest request) {
        OAuthProvider provider = OAuthProvider.GOOGLE;
        String providerUserId = oAuth2User.getAttribute("sub");
        if (providerUserId == null || providerUserId.isBlank()) {
            providerUserId = oAuth2User.getName();
        }

        String email = oAuth2User.getAttribute("email");
        if (email == null || email.isBlank()) {
            throw new BadRequestException("OAuth provider did not return an email address.");
        }
        email = email.trim().toLowerCase();

        Object emailVerified = oAuth2User.getAttribute("email_verified");
        if (Boolean.FALSE.equals(emailVerified) || (emailVerified instanceof String s && "false".equalsIgnoreCase(s.trim()))) {
            throw new BadRequestException("OAuth provider did not verify the email address.");
        }

        String firstName = oAuth2User.getAttribute("given_name");
        String lastName = oAuth2User.getAttribute("family_name");
        String picture = oAuth2User.getAttribute("picture");

        var existingIdentity = oauthIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);

        User user;
        if (existingIdentity.isPresent()) {
            user = existingIdentity.get().getUser();
        } else {
            var existingUserByEmail = userRepository.findByEmail(email);
            if (existingUserByEmail.isPresent()) {
                if (!existingUserByEmail.get().isEmailVerified()) {
                    throw new BadRequestException("An existing account must verify its email before Google sign-in can be linked.");
                }
                user = autoLinkByVerifiedEmail(existingUserByEmail.get(), provider, providerUserId, email);
            } else {
                AccountRole role = parseOAuthIntentRole(intentRole);
                user = createOAuthUserAndProfile(email, provider, providerUserId, firstName, lastName, picture, role);
            }
        }

        validateAccountStatus(user);

        user.setLastLoginAt(LocalDateTime.now(clock));
        user.setLastOAuthLoginAt(LocalDateTime.now(clock));
        if (request != null) {
            user.setLastLoginIp(extractClientIp(request));
        }
        userRepository.save(user);

        return generateJwtResponse(user);
    }

    /**
     * Produces a role-specific login user summary embedded in the JWT response.
     *
     * @param user the authenticated user entity
     * @return a role-specific {@link ProfileResponse} suitable for embedding in the JWT response body
     */
    public ProfileResponse mapToLoginSummary(User user) {
        return profileResponseMapper.mapToProfileResponse(user);
    }

    /**
     * Builds a JWT response DTO from the given user.
     *
     * @param user the authenticated user entity
     * @return the assembled {@link JwtResponseDTO}
     */
    private JwtResponseDTO generateJwtResponse(User user) {
        String accessToken = jwtUtils.generateAccessToken(user.getEmail());
        RefreshToken refreshToken = refreshTokenService.createRefreshTokenForUser(user);

        return JwtResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType(TokenType.BEARER)
                .expiresIn(jwtProperties.getAccessTokenExpirationMs() / MS_PER_SECOND)
                .user(profileResponseMapper.mapToProfileResponse(user))
                .permissions(canonicalPermissions(user))
                .build();
    }

    /**
     * Validates the registration account type and resolves its default permissions.
     * ADMIN registration via the public endpoint is always rejected.
     *
     * @param accountTypeInput the raw role from the registration request, may be {@code null}
     * @return the permissions assigned to the new account
     * @throws BadRequestException       if the role is ADMIN or not one of ARTISAN / CLIENT
     * @throws ResourceNotFoundException if the role does not exist in the database
     */
    private AccountRole parseRegistrationRole(AccountRole accountTypeInput) {
        AccountRole role = Optional.ofNullable(accountTypeInput).orElseThrow(
                () -> new BadRequestException("Invalid account type. Allowed values are ARTISAN or CLIENT."));
        if (role == AccountRole.ADMIN) {
            throw new BadRequestException("Administrator registration is not permitted via public registration.");
        }
        return role;
    }

    private Set<AuthorizationPermission> permissionsForRegistration(boolean artisan) {
        Set<String> keys = artisan
                ? Set.of(Permission.Artisan.CONTENT.value(), Permission.Artisan.FORMATIONS.value(), Permission.Artisan.REVIEWS.value(), Permission.Profile.READ.value(), Permission.Profile.WRITE.value(), Permission.Report.CREATE.value(), Permission.File.READ.value(), Permission.Message.SEND.value())
                : Set.of(Permission.Client.FAVORITES.value(), Permission.Profile.READ.value(), Permission.Profile.WRITE.value(), Permission.Report.CREATE.value(), Permission.File.READ.value(), Permission.Message.SEND.value());
        List<AuthorizationPermission> permissions = permissionRepository.findByPermissionKeyInAndEnabledTrue(keys);
        if (permissions.size() != keys.size()) {
            throw new ResourceNotFoundException(ERROR_PERMISSION_NOT_FOUND_PREFIX + keys);
        }
        return new HashSet<>(permissions);
    }

    private List<Permission> canonicalPermissions(User user) {
        return user.getPermissions().stream()
                .filter(AuthorizationPermission::isEnabled)
                .map(AuthorizationPermission::getPermissionKey)
                .map(Permission::fromValue)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Constructs a new {@link User} entity from the registration DTO, resolved email, role, and initial status.
     * Falls back to splitting the {@code name} field when {@code firstName} is not provided.
     *
     * @param dto           the registration payload
     * @param email         the normalised (lowercase, trimmed) email address
     * @param permissions   the permissions to assign to the new user
     * @param initialStatus the initial {@link AccountStatus} derived from the role
     * @return a fully constructed but not yet persisted {@link User} entity
     */
    private User buildNewUser(UserRegistrationDTO dto, String email, Set<AuthorizationPermission> permissions, AccountStatus initialStatus) {
        String firstName = dto.getFirstName();
        String lastName = dto.getLastName();
        if ((firstName == null || firstName.isBlank()) && dto.getName() != null && !dto.getName().isBlank()) {
            String[] parts = dto.getName().trim().split("\\s+", 2);
            firstName = parts[0];
            lastName = parts.length > 1 ? parts[1] : "";
        }

        return User.builder()
                .email(email)
                .password(passwordEncoder.encode(dto.getPassword()))
                .firstName(firstName)
                .lastName(lastName)
                .status(initialStatus)
                .emailVerified(false)
                .permissions(permissions)
                .build();
    }

    /**
     * Throws {@link ForbiddenException} if the user's account is currently within
     * its temporary lockout window due to repeated failed login attempts.
     *
     * @param user the user entity to check
     * @throws ForbiddenException if the account is locked and the lockout period has not elapsed
     */
    private void assertAccountNotLocked(User user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now(clock))) {
            throw new ForbiddenException("ACCOUNT_LOCKED", "Too many failed login attempts. Account is temporarily locked. Please try again later.");
        }
    }

    private void verifyPasswordCredentials(User user, DeleteAccountRequest request) {
        if (request == null || request.password() == null || request.password().isBlank()) {
            throw new UnauthorizedException("Valid password is required.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new UnauthorizedException("Valid password is required.");
        }
    }

    private void verifyOAuthSession(User user, DeleteAccountRequest request) {
        LocalDateTime lastOAuth = user.getLastOAuthLoginAt();
        if (lastOAuth == null || !lastOAuth.isAfter(LocalDateTime.now(clock).minusMinutes(10))) {
            throw new UnauthorizedException("Recent OAuth confirmation is required.");
        }
        if (request == null || !Boolean.TRUE.equals(request.getOauthConfirmed())) {
            throw new UnauthorizedException("Recent OAuth confirmation is required.");
        }
    }

    /**
     * Increments the user's failed attempt counter, applies an account lockout
     * when the threshold reaches the configured maximum attempts, persists the updated state, and returns
     * {@link UnauthorizedException} to terminate the login flow.
     *
     * @param user the user entity whose counter should be incremented
     * @return UnauthorizedException to signal the password mismatch to the caller
     */
    private UnauthorizedException recordFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        int maxAttempts = authProperties.getLockout().getMaxAttempts();
        int lockoutMinutes = authProperties.getLockout().getDurationMinutes();
        if (attempts >= maxAttempts) {
            user.setLockedUntil(LocalDateTime.now(clock).plusMinutes(lockoutMinutes));
        }
        userRepository.save(user);
        return new UnauthorizedException(ERROR_INVALID_CREDENTIALS);
    }

    /**
     * Lifts a temporary suspension if its end date has passed.
     * If the suspension is still active, throws {@link ForbiddenException}.
     * Accounts with status {@link AccountStatus#REJECTED} always throw.
     *
     * @param user the user entity to evaluate
     * @throws ForbiddenException if the account is actively suspended or was rejected
     */
    private void validateAccountStatus(User user) {
        if (user.getDeletedAt() != null || user.getStatus() == AccountStatus.DELETED) {
            throw new UnauthorizedException("ACCOUNT_DELETED", "Account has been deactivated.");
        }

        if (user.getStatus() == AccountStatus.SUSPENDED) {
            if (!user.isSuspensionActive(LocalDateTime.now(clock))) {
                user.setStatus(AccountStatus.ACTIVE);
                user.setBannedUntil(null);
                user.setBanReason(null);
            } else {
                throw new ForbiddenException("ACCOUNT_SUSPENDED", "Account is suspended: " + (user.getBanReason() != null ? user.getBanReason() : supportProperties.getContactMessage()));
            }
        }

        if (user.getStatus() == AccountStatus.REJECTED) {
            throw new ForbiddenException("ACCOUNT_REJECTED", "Account registration was rejected: " + (user.getBanReason() != null ? user.getBanReason() : supportProperties.getContactMessage()));
        }

        if (user.getStatus() == AccountStatus.PENDING) {
            throw new ForbiddenException("ACCOUNT_PENDING", "Account registration is pending administrator approval.");
        }

        if (!user.isEmailVerified()) {
            throw new ForbiddenException("EMAIL_NOT_VERIFIED", "Please verify your email address before signing in.");
        }
    }

    /**
     * Links a new {@link OAuthIdentity} to an existing {@link User} found by verified email.
     * This covers the case where the user previously registered with a password and is now
     * signing in with Google for the first time.
     *
     * @param user           the existing user to link the identity to
     * @param provider       the OAuth2 provider name
     * @param providerUserId the provider-specific user identifier
     * @param email          the normalised email address
     * @return the same {@link User} instance with the new identity persisted
     */
    private User autoLinkByVerifiedEmail(User user, OAuthProvider provider, String providerUserId, String email) {
        OAuthIdentity identity = OAuthIdentity.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .email(email)
                .build();
        oauthIdentityRepository.save(identity);
        return user;
    }

    /**
     * Creates a brand-new {@link User} and {@link OAuthIdentity} for a first-time OAuth2 registrant.
     * Derives the account status and role from the intent string captured at registration start.
     *
     * @param email          the normalised email address
     * @param provider       the OAuth2 provider name
     * @param providerUserId the provider-specific user identifier
     * @param firstName      the given name returned by the provider
     * @param lastName       the family name returned by the provider
     * @param picture        the avatar URL returned by the provider
     * @param role           the account role (ARTISAN or CLIENT)
     * @return the persisted {@link User} entity
     * @throws BadRequestException       if the intent role is blank or does not match ARTISAN or CLIENT
     * @throws ResourceNotFoundException if the resolved role does not exist in the database
     */
    private User createOAuthUserAndProfile(String email, OAuthProvider provider, String providerUserId,
                                           String firstName, String lastName, String picture,
                                           AccountRole role) {
        boolean artisan = role == AccountRole.ARTISAN;
        AccountStatus initialStatus = artisan ? AccountStatus.PENDING : AccountStatus.ACTIVE;

        User user = User.builder()
                .email(email)
                .password(null)
                .firstName(firstName)
                .lastName(lastName)
                .avatarUrl(picture)
                .status(initialStatus)
                .emailVerified(true)
                .emailVerifiedAt(LocalDateTime.now(clock))
                .permissions(permissionsForRegistration(artisan))
                .build();

        user = userRepository.save(user);

        if (role == AccountRole.CLIENT && clientRepository != null) {
            Client client = Client.builder()
                    .user(user)
                    .clientType(ClientType.INDIVIDUAL.value())
                    .build();
            clientRepository.save(client);
            user.setClient(client);
        }

        OAuthIdentity identity = OAuthIdentity.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .email(email)
                .build();
        oauthIdentityRepository.save(identity);

        return user;
    }

    private AccountRole parseOAuthIntentRole(String intentRole) {
        if (intentRole == null || intentRole.isBlank()) {
            throw new BadRequestException("OAuth registration intent not found or expired. Please initiate registration from the artisan or client signup page.");
        }
        AccountRole role = AccountRole.fromInput(intentRole).orElse(null);
        if (role != AccountRole.ARTISAN && role != AccountRole.CLIENT) {
            throw new BadRequestException("Invalid OAuth registration role intent: " + intentRole);
        }
        return role;
    }

    /**
     * Extracts the originating client IP address from the request.
     * Prefers the first entry in the {@code X-Forwarded-For} header when present,
     * falling back to the direct {@code remoteAddr}.
     *
     * @param request the HTTP servlet request
     * @return the resolved IP address string
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
