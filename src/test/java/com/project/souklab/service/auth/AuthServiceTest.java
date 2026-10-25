package com.project.souklab.service.auth;

import java.util.Collection;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
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
import com.project.souklab.model.User;
import com.project.souklab.model.VerificationTokenType;
import com.project.souklab.security.JwtUtils;
import com.project.souklab.security.Permission;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.profile.ProfileResponseMapper;
import com.project.souklab.service.security.RefreshTokenService;
import com.project.souklab.service.security.VerificationTokenService;
import com.project.souklab.util.EmailUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit test suite for AuthService covering every method and logical branch.
 * Enforces strict mocking with constructor injection and deterministic fixed clock.
 * Profile lifecycle methods (getCurrentUser, completeProfile, patchCurrentUser) are tested in ProfileServiceTest.
 * Mapping methods (mapToProfileResponse, mapToSummaryDTO) are tested in ProfileResponseMapperTest.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private AuthorizationPermissionRepository permissionRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private OAuthIdentityRepository oauthIdentityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationService notificationService;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private VerificationTokenService verificationTokenService;

    @Mock
    private EmailUtil emailUtil;

    @Mock
    private AuditLogService auditLogService;

    private final ProfileResponseMapper profileResponseMapper = new ProfileResponseMapper();

    private JwtProperties jwtProperties;
    private AuthProperties authProperties;
    private SupportProperties supportProperties;
    private Clock fixedClock;
    private LocalDateTime fixedNow;
    private AuthService authService;
    private AuthorizationPermission artisanRole;
    private AuthorizationPermission clientRole;

    /**
     * Initializes test fixtures, fixed clock at 2026-09-04T12:00:00Z, and AuthService instance.
     */
    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneId.of("UTC"));
        fixedNow = LocalDateTime.now(fixedClock);

        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpirationMs(900000L);
        jwtProperties.setRefreshTokenExpirationMs(604800000L);

        authProperties = new AuthProperties();
        authProperties.getLockout().setMaxAttempts(5);
        authProperties.getLockout().setDurationMinutes(15);

        supportProperties = new SupportProperties();

        artisanRole = new AuthorizationPermission();
        artisanRole.setPermissionKey(Permission.Artisan.CONTENT.value());
        artisanRole.setDescription("Artisan role");

        clientRole = new AuthorizationPermission();
        clientRole.setPermissionKey(Permission.Profile.READ.value());
        clientRole.setDescription("Client role");

        lenient().when(permissionRepository.findByPermissionKeyInAndEnabledTrue(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> keys = invocation.getArgument(0);
            if (keys == null) {
                return List.of();
            }
            return keys.stream()
                    .map(key -> key.equals(Permission.Artisan.CONTENT.value()) ? artisanRole : clientRole)
                    .toList();
        });

        authService = new AuthService(
                userRepository,
                clientRepository,
                permissionRepository,
                refreshTokenRepository,
                oauthIdentityRepository,
                passwordEncoder,
                notificationService,
                jwtUtils,
                refreshTokenService,
                jwtProperties,
                authProperties,
                supportProperties,
                verificationTokenService,
                emailUtil,
                auditLogService,
                fixedClock,
                profileResponseMapper
        );
    }

    /**
     * Clears SecurityContextHolder to prevent cross-test authentication leakage.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Verifies registerUser rejects an existing email with ConflictException.
     */
    @Test
    @DisplayName("registerUser: throws ConflictException when email is already registered")
    void registerUser_whenEmailAlreadyExists_throwsConflictException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("Existing@Example.COM")
                .password("password123")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email is already registered: existing@example.com");

        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies registerUser rejects public admin registration with BadRequestException.
     */
    @Test
    @DisplayName("registerUser: throws BadRequestException when role is ADMIN")
    void registerUser_whenRoleIsAdmin_throwsBadRequestException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("admin@example.com")
                .password("password123")
                .accountType(AccountRole.ADMIN)
                .build();

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Administrator registration is not permitted via public registration.");
    }

    /**
     * Verifies registerUser rejects public role admin registration with BadRequestException.
     */
    @Test
    @DisplayName("registerUser: rejects administrator capability during public registration")
    void registerUser_whenRoleIsRoleAdmin_throwsBadRequestException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("admin@example.com")
                .password("password123")
                .accountType(AccountRole.ADMIN)
                .build();

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Administrator registration is not permitted via public registration.");
    }

    /**
     * Verifies registerUser rejects unsupported permissions with BadRequestException.
     */
    @Test
    @DisplayName("registerUser: throws BadRequestException when role is invalid")
    void registerUser_whenRoleIsInvalid_throwsBadRequestException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("someone@example.com")
                .password("password123")
                .accountType(null)
                .build();

        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid account type. Allowed values are ARTISAN or CLIENT.");
    }

    /**
     * Verifies registerUser throws ResourceNotFoundException when role is not present in repository.
     */
    @Test
    @DisplayName("registerUser: throws ResourceNotFoundException when role not found in repository")
    void registerUser_whenRoleNotFoundInRepository_throwsResourceNotFoundException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("password123")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        lenient().when(permissionRepository.findByPermissionKeyInAndEnabledTrue(any())).thenReturn(List.of());

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageStartingWith("Permission not found:");
    }

    /**
     * Verifies successful client registration parses full name, sets ACTIVE status, and sends verification code.
     */
    @Test
    @DisplayName("registerUser: creates active client with split full name and issues verification code")
    void registerUser_withValidClientRoleAndFullName_createsActiveUserAndIssuesVerificationCode() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .name("Jane Doe")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });
        when(verificationTokenService.issueToken(any(User.class), eq(VerificationTokenType.EMAIL_VERIFICATION)))
                .thenReturn("123456");

        ProfileResponse response = authService.registerUser(dto);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getEmail()).isEqualTo("client@example.com");
        assertThat(clientProfile.getFirstName()).isEqualTo("Jane");
        assertThat(clientProfile.getLastName()).isEqualTo("Doe");
        assertThat(clientProfile.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(clientProfile.isEmailVerified()).isFalse();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getPassword()).isEqualTo("encodedPassword123");
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        verify(emailUtil).sendVerificationCodeSynchronous("client@example.com", "123456");
        ArgumentCaptor<Client> clientCaptor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(clientCaptor.capture());
        assertThat(clientCaptor.getValue().getUser().getId()).isEqualTo("client-user-id");
        assertThat(clientCaptor.getValue().getClientType()).isEqualTo(ClientType.INDIVIDUAL.value());
        verifyNoInteractions(notificationService);
    }

    /**
     * Verifies registerUser handles single-word name by setting empty string for last name.
     */
    @Test
    @DisplayName("registerUser: creates active client with empty last name when single name given")
    void registerUser_withValidClientRoleAndSingleName_createsActiveUserWithEmptyLastName() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .name("Jane")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });

        ProfileResponse response = authService.registerUser(dto);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getFirstName()).isEqualTo("Jane");
        assertThat(clientProfile.getLastName()).isEmpty();
    }

    /**
     * Verifies registerUser preserves explicit first and last names without splitting full name.
     */
    @Test
    @DisplayName("registerUser: uses explicit first and last name when provided directly")
    void registerUser_withValidClientRoleAndFirstLastName_createsActiveUserWithoutSplitting() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .firstName("Alice")
                .lastName("Smith")
                .name("Ignored Full Name")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });

        ProfileResponse response = authService.registerUser(dto);

        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getFirstName()).isEqualTo("Alice");
        assertThat(clientProfile.getLastName()).isEqualTo("Smith");
    }

    /**
     * Verifies successful artisan registration sets PENDING status and notifies administrators.
     */
    @Test
    @DisplayName("registerUser: creates pending artisan and notifies administrators")
    void registerUser_withValidArtisanRole_createsPendingUserAndNotifiesAdmins() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("artisan@example.com")
                .password("rawPassword123")
                .firstName("Karim")
                .lastName("Najar")
                .accountType(AccountRole.ARTISAN)
                .build();

        when(userRepository.existsByEmail("artisan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("artisan-user-id");
            return user;
        });
        when(verificationTokenService.issueToken(any(User.class), eq(VerificationTokenType.EMAIL_VERIFICATION)))
                .thenReturn("654321");

        ProfileResponse response = authService.registerUser(dto);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO artisanProfile = (ArtisanResponseDTO) response;
        assertThat(artisanProfile.getEmail()).isEqualTo("artisan@example.com");
        assertThat(artisanProfile.getAccountStatus()).isEqualTo(AccountStatus.PENDING);

        verify(notificationService).notifyAdmins("New artisan registration pending approval: artisan@example.com");
        verify(emailUtil).sendVerificationCodeSynchronous("artisan@example.com", "654321");
    }

    /**
     * Verifies registerUser aborts with 503 when the verification email cannot be delivered,
     * so no unusable account is ever persisted.
     */
    @Test
    @DisplayName("registerUser: throws AppException 503 when verification email delivery fails")
    void registerUser_whenVerificationEmailFails_throwsServiceUnavailableAndAbortsRegistration() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .firstName("Sara")
                .lastName("Ben")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(verificationTokenService.issueToken(any(User.class), eq(VerificationTokenType.EMAIL_VERIFICATION)))
                .thenReturn("123456");
        doThrow(new RuntimeException("Mail server down"))
                .when(emailUtil).sendVerificationCodeSynchronous("client@example.com", "123456");

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Verifies registerUser catches admin notification exception and completes registration.
     */
    @Test
    @DisplayName("registerUser: catches admin notification exception and completes successfully")
    void registerUser_whenAdminNotificationFails_catchesExceptionAndCompletesRegistration() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("artisan@example.com")
                .password("rawPassword123")
                .firstName("Mehdi")
                .lastName("Alami")
                .accountType(AccountRole.ARTISAN)
                .build();

        when(userRepository.existsByEmail("artisan@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("user-artisan-1");
            return user;
        });
        doThrow(new RuntimeException("Notification bus unavailable"))
                .when(notificationService).notifyAdmins(anyString());

        ProfileResponse response = authService.registerUser(dto);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("artisan@example.com");
    }

    /**
     * Verifies login rejects null login identifier with BadRequestException.
     */
    @Test
    @DisplayName("login: throws BadRequestException when login identifier is null")
    void login_whenIdentifierNull_throwsBadRequestException() {
        LoginDTO dto = LoginDTO.builder()
                .email(null)
                .username(null)
                .password("password123")
                .build();

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email is required for login.");
    }

    /**
     * Verifies login rejects blank login identifier with BadRequestException.
     */
    @Test
    @DisplayName("login: throws BadRequestException when login identifier is blank")
    void login_whenIdentifierBlank_throwsBadRequestException() {
        LoginDTO dto = LoginDTO.builder()
                .email("   ")
                .password("password123")
                .build();

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email is required for login.");
    }

    /**
     * Verifies login throws UnauthorizedException when user email does not exist.
     */
    @Test
    @DisplayName("login: throws UnauthorizedException when user not found")
    void login_whenUserNotFound_throwsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("nonexistent@example.com")
                .password("password123")
                .build();

        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");
    }

    /**
     * Verifies login rejects account with null password (social login user) with UnauthorizedException.
     */
    @Test
    @DisplayName("login: throws UnauthorizedException when user has null password")
    void login_whenUserHasNullPassword_throwsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("oauth@example.com")
                .password("password123")
                .build();

        User user = User.builder()
                .email("oauth@example.com")
                .password(null)
                .build();

        when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");
    }

    /**
     * Verifies login rejects account with blank password with UnauthorizedException.
     */
    @Test
    @DisplayName("login: throws UnauthorizedException when user has blank password")
    void login_whenUserHasBlankPassword_throwsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("oauth@example.com")
                .password("password123")
                .build();

        User user = User.builder()
                .email("oauth@example.com")
                .password("   ")
                .build();

        when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");
    }

    /**
     * Verifies login throws BadRequestException when account lockout timestamp is in the future.
     */
    @Test
    @DisplayName("login: throws ForbiddenException when account is temporarily locked")
    void login_whenAccountTemporarilyLocked_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("locked@example.com")
                .password("password123")
                .build();

        User user = User.builder()
                .email("locked@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .status(AccountStatus.ACTIVE)
                .lockedUntil(fixedNow.plusMinutes(10))
                .build();

        when(userRepository.findByEmail("locked@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Too many failed login attempts. Account is temporarily locked. Please try again later.");
    }

    /**
     * Verifies login proceeds when lockedUntil timestamp is in the past.
     */
    @Test
    @DisplayName("login: allows login when account lockout timestamp is in the past")
    void login_whenAccountLockedInPast_allowsLogin() {
        LoginDTO dto = LoginDTO.builder()
                .email("unlocked@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("unlocked@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .lockedUntil(fixedNow.minusMinutes(1))
                .failedLoginAttempts(5)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("unlocked@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("unlocked@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        JwtResponseDTO response = authService.login(dto, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    /**
     * Verifies login increments failed attempts and throws UnauthorizedException on wrong password.
     */
    @Test
    @DisplayName("login: increments failed attempts on first wrong password")
    void login_whenPasswordIncorrectFirstTime_incrementsFailedAttemptsAndThrowsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("wrongPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    /**
     * Verifies login locks account for 15 minutes on fifth consecutive failed attempt.
     */
    @Test
    @DisplayName("login: locks account for 15 minutes on fifth consecutive failed attempt")
    void login_whenPasswordIncorrectFifthTime_locksAccountFor15MinutesAndThrowsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("wrongPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts(4)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isEqualTo(fixedNow.plusMinutes(15));
        verify(userRepository).save(user);
    }

    /**
     * Verifies login locks account using custom configured max attempts and lockout duration from AuthProperties.
     */
    @Test
    @DisplayName("login: locks account using custom configured max attempts and lockout duration")
    void login_whenCustomLockoutConfigured_locksAccountWithCustomAttemptsAndDuration() {
        authProperties.getLockout().setMaxAttempts(3);
        authProperties.getLockout().setDurationMinutes(30);

        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("wrongPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts(2)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(user.getLockedUntil()).isEqualTo(fixedNow.plusMinutes(30));
        verify(userRepository).save(user);
    }

    /**
     * Verifies login throws ForbiddenException with custom reason when account status is SUSPENDED.
     */
    @Test
    @DisplayName("login: throws ForbiddenException with ban reason when account is suspended")
    void login_whenAccountStatusSuspendedWithReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("suspended@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("suspended@example.com")
                .password("hashedPassword")
                .status(AccountStatus.SUSPENDED)
                .banReason("Terms of service breach")
                .build();

        when(userRepository.findByEmail("suspended@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account is suspended: Terms of service breach");
    }

    /**
     * Verifies login throws ForbiddenException with default message when suspended user has no reason.
     */
    @Test
    @DisplayName("login: throws ForbiddenException with fallback message when suspended without reason")
    void login_whenAccountStatusSuspendedWithoutReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("suspended@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("suspended@example.com")
                .password("hashedPassword")
                .status(AccountStatus.SUSPENDED)
                .banReason(null)
                .build();

        when(userRepository.findByEmail("suspended@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account is suspended: null");
    }

    /**
     * Verifies login throws ForbiddenException when bannedUntil timestamp is in the future.
     */
    @Test
    @DisplayName("login: throws ForbiddenException when bannedUntil is in future")
    void login_whenAccountBannedUntilFutureWithReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("banned@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("banned@example.com")
                .password("hashedPassword")
                .status(AccountStatus.SUSPENDED)
                .bannedUntil(fixedNow.plusDays(2))
                .banReason("Suspicious login activity")
                .build();

        when(userRepository.findByEmail("banned@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account is suspended: Suspicious login activity");
    }

    /**
     * Verifies login throws ForbiddenException with fallback when bannedUntil is in future with no reason.
     */
    @Test
    @DisplayName("login: throws ForbiddenException with fallback when bannedUntil in future without reason")
    void login_whenAccountBannedUntilFutureWithoutReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("banned@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("banned@example.com")
                .password("hashedPassword")
                .status(AccountStatus.SUSPENDED)
                .bannedUntil(fixedNow.plusDays(2))
                .banReason(null)
                .build();

        when(userRepository.findByEmail("banned@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account is suspended: null");
    }

    /**
     * Verifies login allows sign-in when account is SUSPENDED and bannedUntil timestamp is in the past,
     * lazily auto-reinstating the user to ACTIVE and clearing timeout fields.
     */
    @Test
    @DisplayName("login: allows sign-in when bannedUntil is in the past and auto-reinstates account")
    void login_whenAccountBannedUntilInPast_allowsLogin() {
        LoginDTO dto = LoginDTO.builder()
                .email("unbanned@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("unbanned@example.com")
                .password("hashedPassword")
                .status(AccountStatus.SUSPENDED)
                .emailVerified(true)
                .bannedUntil(fixedNow.minusDays(1))
                .banReason("Prior timeout")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("unbanned@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("unbanned@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        JwtResponseDTO response = authService.login(dto, null);

        assertThat(response).isNotNull();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.getBannedUntil()).isNull();
        assertThat(user.getBanReason()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(fixedNow);
        verify(userRepository).save(user);
    }

    /**
     * Verifies login throws ForbiddenException when account status is REJECTED with reason.
     */
    @Test
    @DisplayName("login: throws ForbiddenException when registration rejected with reason")
    void login_whenAccountRegistrationRejectedWithReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("rejected@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("rejected@example.com")
                .password("hashedPassword")
                .status(AccountStatus.REJECTED)
                .banReason("Invalid craftsmanship portfolio")
                .build();

        when(userRepository.findByEmail("rejected@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account registration was rejected: Invalid craftsmanship portfolio");
    }

    /**
     * Verifies login throws ForbiddenException with fallback when account status is REJECTED without reason.
     */
    @Test
    @DisplayName("login: throws ForbiddenException with fallback when registration rejected without reason")
    void login_whenAccountRegistrationRejectedWithoutReason_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("rejected@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("rejected@example.com")
                .password("hashedPassword")
                .status(AccountStatus.REJECTED)
                .banReason(null)
                .build();

        when(userRepository.findByEmail("rejected@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account registration was rejected: null");
    }

    /**
     * Verifies valid login resets failed attempts, clears lockout, updates login timestamp and issues tokens.
     */
    @Test
    @DisplayName("login: successful login with null request resets failed attempts and returns tokens")
    void login_withValidCredentialsAndNullRequest_resetsFailedAttemptsAndReturnsJwtResponse() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .failedLoginAttempts(3)
                .lockedUntil(null)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("user@example.com")).thenReturn("access-token-jwt");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token-uuid").build());

        JwtResponseDTO response = authService.login(dto, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-jwt");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token-uuid");
        assertThat(response.getTokenType()).isEqualTo(TokenType.BEARER);
        assertThat(response.getExpiresIn()).isEqualTo(900L);
        assertThat(response.getPermissions()).containsExactly(Permission.Profile.READ);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(fixedNow);
        assertThat(user.getLastLoginIp()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("login: rejects an active account whose email is not verified")
    void login_whenEmailIsUnverified_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("unverified@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("unverified@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(false)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Please verify your email address before signing in.");
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("login: rejects an account that is soft-deleted")
    void login_whenUserSoftDeleted_throwsForbiddenException() {
        LoginDTO dto = LoginDTO.builder()
                .email("softdeleted@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("softdeleted@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setDeletedAt(fixedNow);

        when(userRepository.findByEmail("softdeleted@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Account has been deactivated.");
    }

    @Test
    @DisplayName("login: rejects an account whose status is DELETED")
    void login_whenUserStatusDeleted_throwsUnauthorizedException() {
        LoginDTO dto = LoginDTO.builder()
                .email("deleted@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("deleted@example.com")
                .password("hashedPassword")
                .status(AccountStatus.DELETED)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("deleted@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Account has been deactivated.");
    }

    @Test
    @DisplayName("refreshToken: rejects a soft-deleted user")
    void refreshToken_whenUserSoftDeleted_throwsUnauthorizedException() {
        TokenRefreshRequestDTO request = new TokenRefreshRequestDTO();
        request.setRefreshToken("old-refresh-token");

        User user = User.builder()
                .email("softdeleted@example.com")
                .build();
        user.setDeletedAt(fixedNow);

        RefreshToken oldToken = RefreshToken.builder()
                .token("old-refresh-token")
                .user(user)
                .build();

        RefreshToken newToken = RefreshToken.builder()
                .token("new-refresh-token")
                .user(user)
                .build();

        when(refreshTokenRepository.findByToken("old-refresh-token")).thenReturn(Optional.of(oldToken));
        when(refreshTokenService.rotateRefreshToken(oldToken)).thenReturn(newToken);

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Account has been deactivated.");
    }

    @Test
    @DisplayName("resetPassword: rejects soft-deleted user with BadRequestException")
    void resetPassword_whenUserSoftDeleted_throwsBadRequestException() {
        ResetPasswordRequestDTO dto = new ResetPasswordRequestDTO();
        dto.setEmail("softdeleted@example.com");
        dto.setCode("123456");
        dto.setNewPassword("newPassword123!");

        User user = User.builder()
                .email("softdeleted@example.com")
                .build();
        user.setDeletedAt(fixedNow);

        when(userRepository.findByEmail("softdeleted@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resetPassword(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired code.");
    }

    /**
     * Verifies login parses first client IP from X-Forwarded-For header.
     */
    @Test
    @DisplayName("login: extracts client IP from X-Forwarded-For header")
    void login_withValidCredentialsAndXForwardedForHeader_recordsClientIp() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("user@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        authService.login(dto, request);

        assertThat(user.getLastLoginIp()).isEqualTo("203.0.113.195");
    }

    /**
     * Verifies login falls back to getRemoteAddr when X-Forwarded-For header is absent.
     */
    @Test
    @DisplayName("login: extracts remote address when X-Forwarded-For is absent")
    void login_withValidCredentialsAndRemoteAddr_recordsRemoteAddr() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("user@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        authService.login(dto, request);

        assertThat(user.getLastLoginIp()).isEqualTo("192.168.1.100");
    }

    /**
     * Verifies login supports username field when email field is absent in LoginDTO.
     */
    @Test
    @DisplayName("login: resolves identifier from username field when email is null")
    void login_withUsernameIdentifier_findsUserByEmail() {
        LoginDTO dto = LoginDTO.builder()
                .email(null)
                .username("artisan@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("artisan@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("artisan@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        JwtResponseDTO response = authService.login(dto, null);

        assertThat(response.getPermissions()).containsExactly(Permission.Artisan.CONTENT);
    }

    /**
     * Verifies refreshToken throws UnauthorizedException when token does not exist in repository.
     */
    @Test
    @DisplayName("refreshToken: throws UnauthorizedException when refresh token not found")
    void refreshToken_whenTokenNotFound_throwsUnauthorizedException() {
        TokenRefreshRequestDTO request = new TokenRefreshRequestDTO();
        request.setRefreshToken("unknown-refresh-token");

        when(refreshTokenRepository.findByToken("unknown-refresh-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid refresh token.");
    }

    /**
     * Verifies refreshToken rotates valid token and returns new token pair.
     */
    @Test
    @DisplayName("refreshToken: rotates valid token and returns new JWT response")
    void refreshToken_whenTokenValid_rotatesTokenAndReturnsNewJwtResponse() {
        TokenRefreshRequestDTO request = new TokenRefreshRequestDTO();
        request.setRefreshToken("old-refresh-token");

        User user = User.builder()
                .email("user@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        RefreshToken oldToken = RefreshToken.builder()
                .token("old-refresh-token")
                .user(user)
                .build();

        RefreshToken newToken = RefreshToken.builder()
                .token("new-refresh-token")
                .user(user)
                .build();

        when(refreshTokenRepository.findByToken("old-refresh-token")).thenReturn(Optional.of(oldToken));
        when(refreshTokenService.rotateRefreshToken(oldToken)).thenReturn(newToken);
        when(jwtUtils.generateAccessToken("user@example.com")).thenReturn("new-access-token");

        JwtResponseDTO response = authService.refreshToken(request);

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.getTokenType()).isEqualTo(TokenType.BEARER);
        assertThat(response.getExpiresIn()).isEqualTo(900L);
    }

    /**
     * Verifies logout deletes specified refresh token and deletes all user tokens by email.
     */
    @Test
    @DisplayName("logout: deletes token and deletes user refresh tokens when both provided")
    void logout_withTokenAndEmail_deletesTokenAndUserRefreshTokens() {
        User user = User.builder().email("user@example.com").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        authService.logout("user@example.com", "token-to-revoke");

        verify(refreshTokenRepository).deleteByToken("token-to-revoke");
        verify(refreshTokenService).deleteByUser(user);
    }

    /**
     * Verifies logout executes safely without exceptions when both arguments are null or blank.
     */
    @Test
    @DisplayName("logout: handles null and blank arguments without errors")
    void logout_withNullAndBlankArguments_executesSafelyWithoutInteractingWithRepositories() {
        authService.logout(null, "");
        authService.logout("   ", null);

        verifyNoInteractions(refreshTokenRepository);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(refreshTokenService);
    }

    /**
     * Verifies logout deletes token only when email argument is null.
     */
    @Test
    @DisplayName("logout: deletes token only when email is null")
    void logout_withTokenOnly_deletesTokenWithoutUserLookup() {
        authService.logout(null, "token-only-xyz");

        verify(refreshTokenRepository).deleteByToken("token-only-xyz");
        verifyNoInteractions(userRepository);
        verifyNoInteractions(refreshTokenService);
    }

    /**
     * Verifies logout deletes user tokens only when token argument is null.
     */
    @Test
    @DisplayName("logout: deletes user refresh tokens only when token argument is null")
    void logout_withEmailOnly_deletesUserRefreshTokensWithoutTokenLookup() {
        User user = User.builder().email("user@example.com").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        authService.logout("user@example.com", null);

        verifyNoInteractions(refreshTokenRepository);
        verify(refreshTokenService).deleteByUser(user);
    }

    /**
     * Verifies logout does not throw when email is not found in repository.
     */
    @Test
    @DisplayName("logout: does not throw when email not found in repository")
    void logout_whenEmailNotFoundInRepository_doesNotThrow() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        authService.logout("ghost@example.com", "token-123");

        verify(refreshTokenRepository).deleteByToken("token-123");
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("logout: request overload revokes the supplied refresh token")
    void logout_requestOverload_revokesSuppliedToken() {
        TokenRefreshRequestDTO request = new TokenRefreshRequestDTO();
        request.setRefreshToken("request-token");

        authService.logout(request);
        authService.logout((TokenRefreshRequestDTO) null);

        verify(refreshTokenRepository).deleteByToken("request-token");
    }

    /**
     * Verifies processOAuth2Success throws BadRequestException when provider returns null or blank email.
     */
    @Test
    @DisplayName("processOAuth2Success: throws BadRequestException when email missing from OAuth provider")
    void processOAuth2Success_whenEmailMissingFromOAuthUser_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-123");
        when(oAuth2User.getAttribute("email")).thenReturn(null);

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OAuth provider did not return an email address.");
    }

    @Test
    @DisplayName("processOAuth2Success: rejects an OAuth identity with an unverified email")
    void processOAuth2Success_whenEmailIsUnverified_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-unverified");
        when(oAuth2User.getAttribute("email")).thenReturn("user@example.com");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(Boolean.FALSE);

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OAuth provider did not verify the email address.");
    }

    @Test
    @DisplayName("processOAuth2Success: rejects an OAuth identity when email_verified is string false")
    void processOAuth2Success_whenEmailVerifiedIsStringFalse_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-string-false");
        when(oAuth2User.getAttribute("email")).thenReturn("user@example.com");
        when(oAuth2User.getAttribute("email_verified")).thenReturn("false");

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OAuth provider did not verify the email address.");
    }

    @Test
    @DisplayName("processOAuth2Success: refuses linking an existing unverified account")
    void processOAuth2Success_whenExistingEmailIsUnverified_rejectsLinking() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-existing-unverified");
        when(oAuth2User.getAttribute("email")).thenReturn("unverified@example.com");
        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-existing-unverified"))
                .thenReturn(Optional.empty());
        User existing = User.builder()
                .email("unverified@example.com")
                .emailVerified(false)
                .build();
        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must verify its email");
    }

    /**
     * Verifies processOAuth2Success logs in existing user matching provider and sub ID.
     */
    @Test
    @DisplayName("processOAuth2Success: logs in existing user matching OAuth identity")
    void processOAuth2Success_whenOAuthIdentityExists_logsInExistingUser() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-123");
        when(oAuth2User.getAttribute("email")).thenReturn("existing@example.com");

        User existingUser = User.builder()
                .email("existing@example.com")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        OAuthIdentity identity = OAuthIdentity.builder()
                .user(existingUser)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(identity));
        when(jwtUtils.generateAccessToken("existing@example.com")).thenReturn("access-token-oauth");
        when(refreshTokenService.createRefreshTokenForUser(existingUser))
                .thenReturn(RefreshToken.builder().token("refresh-token-oauth").build());

        JwtResponseDTO response = authService.processOAuth2Success(oAuth2User, null, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-oauth");
        assertThat(existingUser.getLastLoginAt()).isEqualTo(fixedNow);
        verify(userRepository).save(existingUser);
    }

    /**
     * Verifies processOAuth2Success falls back to getName when sub attribute is blank.
     */
    @Test
    @DisplayName("processOAuth2Success: falls back to getName when sub attribute is blank")
    void processOAuth2Success_whenSubAttributeBlank_fallsBackToOAuthUserName() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("   ");
        when(oAuth2User.getName()).thenReturn("google-name-456");
        when(oAuth2User.getAttribute("email")).thenReturn("existing@example.com");

        User existingUser = User.builder()
                .email("existing@example.com")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        OAuthIdentity identity = OAuthIdentity.builder()
                .user(existingUser)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-name-456")
                .build();

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-name-456"))
                .thenReturn(Optional.of(identity));
        when(jwtUtils.generateAccessToken("existing@example.com")).thenReturn("access-token-oauth");
        when(refreshTokenService.createRefreshTokenForUser(existingUser))
                .thenReturn(RefreshToken.builder().token("refresh-token-oauth").build());

        JwtResponseDTO response = authService.processOAuth2Success(oAuth2User, null, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-oauth");
    }

    /**
     * Verifies processOAuth2Success auto-links identity to existing user when email matches.
     */
    @Test
    @DisplayName("processOAuth2Success: auto-links OAuth identity to existing user by email")
    void processOAuth2Success_whenUserExistsByEmail_linksOAuthIdentityAndLogsIn() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-new");
        when(oAuth2User.getAttribute("email")).thenReturn("match@example.com");

        User existingUser = User.builder()
                .email("match@example.com")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-new"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("match@example.com")).thenReturn(Optional.of(existingUser));
        when(jwtUtils.generateAccessToken("match@example.com")).thenReturn("access-token-match");
        when(refreshTokenService.createRefreshTokenForUser(existingUser))
                .thenReturn(RefreshToken.builder().token("refresh-token-match").build());

        JwtResponseDTO response = authService.processOAuth2Success(oAuth2User, null, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-match");
        ArgumentCaptor<OAuthIdentity> captor = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(oauthIdentityRepository).save(captor.capture());
        OAuthIdentity linked = captor.getValue();
        assertThat(linked.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(linked.getProviderUserId()).isEqualTo("google-sub-new");
        assertThat(linked.getUser()).isEqualTo(existingUser);
    }

    /**
     * Verifies processOAuth2Success throws BadRequestException for new user when intent role is null.
     */
    @Test
    @DisplayName("processOAuth2Success: throws BadRequestException when new user has null intent role")
    void processOAuth2Success_whenNewUserAndIntentRoleNull_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-999");
        when(oAuth2User.getAttribute("email")).thenReturn("newuser@example.com");

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-999"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("newuser@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("OAuth registration intent not found or expired.");
    }

    /**
     * Verifies processOAuth2Success throws BadRequestException for new user when intent role is invalid.
     */
    @Test
    @DisplayName("processOAuth2Success: throws BadRequestException when new user has invalid intent role")
    void processOAuth2Success_whenNewUserAndIntentRoleInvalid_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-999");
        when(oAuth2User.getAttribute("email")).thenReturn("newuser@example.com");

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-999"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("newuser@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, "SUPERUSER", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid OAuth registration role intent: SUPERUSER");
    }

    /**
     * Verifies processOAuth2Success creates pending artisan for new user with artisan intent.
     */
    @Test
    @DisplayName("processOAuth2Success: creates pending artisan user for artisan intent")
    void processOAuth2Success_whenNewUserWithArtisanIntent_createsPendingArtisanAndLogsIn() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-artisan");
        when(oAuth2User.getAttribute("email")).thenReturn("newartisan@example.com");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(Boolean.TRUE);
        when(oAuth2User.getAttribute("given_name")).thenReturn("Ahmed");
        when(oAuth2User.getAttribute("family_name")).thenReturn("Tazi");
        when(oAuth2User.getAttribute("picture")).thenReturn("https://photos.google.com/ahmed.jpg");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-artisan"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("newartisan@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, "ARTISAN", request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account registration is pending administrator approval.");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("newartisan@example.com");
        assertThat(savedUser.getPassword()).isNull();
        assertThat(savedUser.getFirstName()).isEqualTo("Ahmed");
        assertThat(savedUser.getLastName()).isEqualTo("Tazi");
        assertThat(savedUser.getAvatarUrl()).isEqualTo("https://photos.google.com/ahmed.jpg");
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(savedUser.isEmailVerified()).isTrue();
        assertThat(savedUser.getEmailVerifiedAt()).isEqualTo(fixedNow);

        ArgumentCaptor<OAuthIdentity> identityCaptor = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(oauthIdentityRepository).save(identityCaptor.capture());
        OAuthIdentity savedIdentity = identityCaptor.getValue();
        assertThat(savedIdentity.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(savedIdentity.getProviderUserId()).isEqualTo("google-sub-artisan");
        assertThat(savedIdentity.getEmail()).isEqualTo("newartisan@example.com");
        assertThat(savedIdentity.getUser()).isEqualTo(savedUser);
    }

    /**
     * Verifies processOAuth2Success creates active client for new user with client intent.
     */
    @Test
    @DisplayName("processOAuth2Success: creates active client user for client intent")
    void processOAuth2Success_whenNewUserWithClientIntent_createsActiveClientAndLogsIn() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-client");
        when(oAuth2User.getAttribute("email")).thenReturn("newclient@example.com");
        when(oAuth2User.getAttribute("email_verified")).thenReturn(Boolean.TRUE);
        when(oAuth2User.getAttribute("given_name")).thenReturn("Laila");
        when(oAuth2User.getAttribute("family_name")).thenReturn("Fassi");
        when(oAuth2User.getAttribute("picture")).thenReturn(null);

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-client"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("newclient@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtUtils.generateAccessToken("newclient@example.com")).thenReturn("access-token-client");
        when(refreshTokenService.createRefreshTokenForUser(any(User.class)))
                .thenReturn(RefreshToken.builder().token("refresh-token-client").build());

        JwtResponseDTO response = authService.processOAuth2Success(oAuth2User, "CLIENT", null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-client");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("newclient@example.com");
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(savedUser.isEmailVerified()).isTrue();
        assertThat(savedUser.getEmailVerifiedAt()).isEqualTo(fixedNow);

        ArgumentCaptor<OAuthIdentity> identityCaptor = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(oauthIdentityRepository).save(identityCaptor.capture());
        OAuthIdentity savedIdentity = identityCaptor.getValue();
        assertThat(savedIdentity.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(savedIdentity.getProviderUserId()).isEqualTo("google-sub-client");
        assertThat(savedIdentity.getEmail()).isEqualTo("newclient@example.com");
        assertThat(savedIdentity.getUser()).isEqualTo(savedUser);
    }

    /**
     * Verifies processOAuth2Success throws ResourceNotFoundException when role is not in repository.
     */
    @Test
    @DisplayName("processOAuth2Success: throws ResourceNotFoundException when assigned role is missing")
    void processOAuth2Success_whenNewUserAndRoleNotFoundInRepository_throwsResourceNotFoundException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-role-missing");
        when(oAuth2User.getAttribute("email")).thenReturn("norole@example.com");

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-role-missing"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("norole@example.com")).thenReturn(Optional.empty());
        lenient().when(permissionRepository.findByPermissionKeyInAndEnabledTrue(any())).thenReturn(List.of());

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, "ARTISAN", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageStartingWith("Permission not found:");
    }

    /**
     * Verifies mapToLoginSummary delegates to profileResponseMapper.
     */
    @Test
    @DisplayName("mapToLoginSummary: delegates directly to profileResponseMapper")
    void mapToLoginSummary_delegatesToProfileResponseMapper() {
        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        ProfileResponse summary = authService.mapToLoginSummary(user);

        assertThat(summary).isNotNull();
        assertThat(summary.getEmail()).isEqualTo("client@example.com");
    }

    /**
     * Verifies verifyEmail throws BadRequestException when user is not found to prevent user enumeration.
     */
    @Test
    @DisplayName("verifyEmail: throws BadRequestException when user not found to prevent enumeration")
    void verifyEmail_whenUserNotFound_throwsBadRequestException() {
        VerifyEmailRequestDTO dto = VerifyEmailRequestDTO.builder()
                .email("missing@example.com")
                .code("123456")
                .build();

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired code.");
    }

    /**
     * Verifies verifyEmail propagates BadRequestException when token service rejects code.
     */
    @Test
    @DisplayName("verifyEmail: propagates BadRequestException when code is invalid or expired")
    void verifyEmail_whenTokenServiceRejectsCode_propagatesException() {
        VerifyEmailRequestDTO dto = VerifyEmailRequestDTO.builder()
                .email("user@example.com")
                .code("000000")
                .build();

        User user = User.builder().email("user@example.com").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        doThrow(new BadRequestException("Invalid code"))
                .when(verificationTokenService).validateAndConsume(user, VerificationTokenType.EMAIL_VERIFICATION, "000000");

        assertThatThrownBy(() -> authService.verifyEmail(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid code");
    }

    /**
     * Verifies verifyEmail marks email as verified, records timestamp, saves user and logs audit entry.
     */
    @Test
    @DisplayName("verifyEmail: marks email verified and records audit log")
    void verifyEmail_withValidCode_marksEmailVerifiedAndLogsAudit() {
        VerifyEmailRequestDTO dto = VerifyEmailRequestDTO.builder()
                .email("user@example.com")
                .code("654321")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .emailVerified(false)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        authService.verifyEmail(dto);

        verify(verificationTokenService).validateAndConsume(user, VerificationTokenType.EMAIL_VERIFICATION, "654321");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerifiedAt()).isEqualTo(fixedNow);
        verify(userRepository).save(user);
        verify(auditLogService).logAction(AuditLogAction.Authentication.Email.VERIFIED,
                "Email verified for user: user@example.com", "user@example.com");
    }

    /**
     * Verifies resendVerification returns silently when email is not registered (anti-enumeration).
     */
    @Test
    @DisplayName("resendVerification: returns silently when user does not exist")
    void resendVerification_whenUserNotFound_returnsSilentlyWithoutError() {
        ResendVerificationRequestDTO dto = ResendVerificationRequestDTO.builder()
                .email("ghost@example.com")
                .build();

        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        authService.resendVerification(dto);

        verifyNoInteractions(verificationTokenService);
        verifyNoInteractions(emailUtil);
    }

    /**
     * Verifies resendVerification returns silently without issuing code when user is already verified.
     */
    @Test
    @DisplayName("resendVerification: returns silently when user is already verified")
    void resendVerification_whenUserAlreadyVerified_doesNotIssueTokenOrSendEmail() {
        ResendVerificationRequestDTO dto = ResendVerificationRequestDTO.builder()
                .email("verified@example.com")
                .build();

        User user = User.builder()
                .email("verified@example.com")
                .emailVerified(true)
                .build();

        when(userRepository.findByEmail("verified@example.com")).thenReturn(Optional.of(user));

        authService.resendVerification(dto);

        verifyNoInteractions(verificationTokenService);
        verifyNoInteractions(emailUtil);
    }

    /**
     * Verifies resendVerification issues token and sends code when user is unverified.
     */
    @Test
    @DisplayName("resendVerification: issues token and dispatches email when user is unverified")
    void resendVerification_whenUserUnverified_issuesTokenAndSendsVerificationEmail() {
        ResendVerificationRequestDTO dto = ResendVerificationRequestDTO.builder()
                .email("unverified@example.com")
                .build();

        User user = User.builder()
                .email("unverified@example.com")
                .emailVerified(false)
                .build();

        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(user));
        when(verificationTokenService.issueToken(user, VerificationTokenType.EMAIL_VERIFICATION))
                .thenReturn("888999");

        authService.resendVerification(dto);

        verify(emailUtil).sendVerificationCode("unverified@example.com", "888999");
    }

    /**
     * Verifies resendVerification catches email dispatch failure without throwing exception.
     */
    @Test
    @DisplayName("resendVerification: catches email sending exception and does not propagate")
    void resendVerification_whenEmailSendFails_catchesExceptionAndDoesNotThrow() {
        ResendVerificationRequestDTO dto = ResendVerificationRequestDTO.builder()
                .email("unverified@example.com")
                .build();

        User user = User.builder()
                .email("unverified@example.com")
                .emailVerified(false)
                .build();

        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(user));
        when(verificationTokenService.issueToken(user, VerificationTokenType.EMAIL_VERIFICATION))
                .thenReturn("888999");
        doThrow(new RuntimeException("SMTP connection timeout"))
                .when(emailUtil).sendVerificationCode(anyString(), anyString());

        assertDoesNotThrow(() -> authService.resendVerification(dto));
        verify(emailUtil).sendVerificationCode(eq("unverified@example.com"), eq("888999"));
    }

    /**
     * Verifies forgotPassword returns silently when email does not exist (anti-enumeration).
     */
    @Test
    @DisplayName("forgotPassword: returns silently when user does not exist")
    void forgotPassword_whenUserNotFound_returnsSilentlyWithoutError() {
        ForgotPasswordRequestDTO dto = ForgotPasswordRequestDTO.builder()
                .email("unknown@example.com")
                .build();

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(dto);

        verifyNoInteractions(verificationTokenService);
        verifyNoInteractions(emailUtil);
    }

    /**
     * Verifies forgotPassword issues reset token and sends code when user has a password.
     */
    @Test
    @DisplayName("forgotPassword: issues password reset token and sends email when user has password")
    void forgotPassword_whenUserHasPassword_issuesTokenAndSendsResetCodeEmail() {
        ForgotPasswordRequestDTO dto = ForgotPasswordRequestDTO.builder()
                .email("user@example.com")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(verificationTokenService.issueToken(user, VerificationTokenType.PASSWORD_RESET))
                .thenReturn("777111");

        authService.forgotPassword(dto);

        verify(emailUtil).sendPasswordResetCode("user@example.com", "777111");
    }

    /**
     * Verifies forgotPassword sends informational notice when user has no password (OAuth-only).
     */
    @Test
    @DisplayName("forgotPassword: sends OAuth-only notice when user has no password")
    void forgotPassword_whenUserHasNoPassword_sendsOAuthNoticeEmail() {
        ForgotPasswordRequestDTO dto = ForgotPasswordRequestDTO.builder()
                .email("oauthonly@example.com")
                .build();

        User user = User.builder()
                .email("oauthonly@example.com")
                .password(null)
                .build();

        when(userRepository.findByEmail("oauthonly@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword(dto);

        verify(emailUtil).sendOAuthOnlyPasswordResetNotice("oauthonly@example.com");
        verifyNoInteractions(verificationTokenService);
    }

    /**
     * Verifies forgotPassword catches reset email failure without throwing.
     */
    @Test
    @DisplayName("forgotPassword: catches reset email dispatch failure without throwing")
    void forgotPassword_whenPasswordResetEmailFails_catchesExceptionAndDoesNotThrow() {
        ForgotPasswordRequestDTO dto = ForgotPasswordRequestDTO.builder()
                .email("user@example.com")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(verificationTokenService.issueToken(user, VerificationTokenType.PASSWORD_RESET))
                .thenReturn("777111");
        doThrow(new RuntimeException("Mail failure")).when(emailUtil).sendPasswordResetCode(anyString(), anyString());

        assertDoesNotThrow(() -> authService.forgotPassword(dto));
        verify(emailUtil).sendPasswordResetCode(eq("user@example.com"), eq("777111"));
    }

    /**
     * Verifies forgotPassword catches OAuth notice email failure without throwing.
     */
    @Test
    @DisplayName("forgotPassword: catches OAuth notice dispatch failure without throwing")
    void forgotPassword_whenOAuthNoticeEmailFails_catchesExceptionAndDoesNotThrow() {
        ForgotPasswordRequestDTO dto = ForgotPasswordRequestDTO.builder()
                .email("oauthonly@example.com")
                .build();

        User user = User.builder()
                .email("oauthonly@example.com")
                .password("")
                .build();

        when(userRepository.findByEmail("oauthonly@example.com")).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("Mail failure")).when(emailUtil).sendOAuthOnlyPasswordResetNotice(anyString());

        assertDoesNotThrow(() -> authService.forgotPassword(dto));
        verify(emailUtil).sendOAuthOnlyPasswordResetNotice(eq("oauthonly@example.com"));
    }

    /**
     * Verifies resetPassword throws BadRequestException when email is not found.
     */
    @Test
    @DisplayName("resetPassword: throws BadRequestException when user not found")
    void resetPassword_whenUserNotFound_throwsBadRequestException() {
        ResetPasswordRequestDTO dto = ResetPasswordRequestDTO.builder()
                .email("missing@example.com")
                .code("123456")
                .newPassword("brandNewPassword123")
                .build();

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid or expired code.");
    }

    /**
     * Verifies resetPassword propagates BadRequestException when verification code is invalid.
     */
    @Test
    @DisplayName("resetPassword: propagates BadRequestException when code validation fails")
    void resetPassword_whenTokenServiceRejectsCode_propagatesException() {
        ResetPasswordRequestDTO dto = ResetPasswordRequestDTO.builder()
                .email("user@example.com")
                .code("000000")
                .newPassword("brandNewPassword123")
                .build();

        User user = User.builder().email("user@example.com").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        doThrow(new BadRequestException("Code expired"))
                .when(verificationTokenService).validateAndConsume(user, VerificationTokenType.PASSWORD_RESET, "000000");

        assertThatThrownBy(() -> authService.resetPassword(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Code expired");
    }

    /**
     * Verifies resetPassword updates password, deletes refresh tokens, and records audit log.
     */
    @Test
    @DisplayName("resetPassword: encodes password, deletes refresh tokens, and records audit log")
    void resetPassword_withValidCode_encodesPasswordDeletesTokensAndLogsAudit() {
        ResetPasswordRequestDTO dto = ResetPasswordRequestDTO.builder()
                .email("user@example.com")
                .code("555444")
                .newPassword("brandNewPassword123")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("oldHashedPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("brandNewPassword123")).thenReturn("newlyHashedPassword");

        authService.resetPassword(dto);

        verify(verificationTokenService).validateAndConsume(user, VerificationTokenType.PASSWORD_RESET, "555444");
        assertThat(user.getPassword()).isEqualTo("newlyHashedPassword");
        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(auditLogService).logAction(AuditLogAction.Authentication.Password.Reset.COMPLETED,
                "Password reset completed for user: user@example.com", "user@example.com");
    }

    /**
     * Verifies changePassword throws UnauthorizedException when user is unauthenticated.
     */
    @Test
    @DisplayName("changePassword: throws UnauthorizedException when unauthenticated")
    void changePassword_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("oldPassword123")
                .newPassword("newPassword456")
                .build();

        assertThatThrownBy(() -> authService.changePassword(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated.");
    }

    /**
     * Verifies changePassword throws ResourceNotFoundException when authenticated user not in DB.
     */
    @Test
    @DisplayName("changePassword: throws ResourceNotFoundException when authenticated user not in DB")
    void changePassword_whenUserNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("missing@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("oldPassword123")
                .newPassword("newPassword456")
                .build();

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: missing@example.com");
    }

    /**
     * Verifies changePassword rejects OAuth-only users who have no existing password.
     */
    @Test
    @DisplayName("changePassword: throws BadRequestException when user has null password")
    void changePassword_whenUserHasNoPassword_throwsBadRequestException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("oauth@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("oldPassword123")
                .newPassword("newPassword456")
                .build();

        User user = User.builder()
                .email("oauth@example.com")
                .password(null)
                .build();

        when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.changePassword(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("This account was created via social login");
    }

    /**
     * Verifies changePassword rejects OAuth-only users who have blank existing password.
     */
    @Test
    @DisplayName("changePassword: throws BadRequestException when user has blank password")
    void changePassword_whenUserHasBlankPassword_throwsBadRequestException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("oauth@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("oldPassword123")
                .newPassword("newPassword456")
                .build();

        User user = User.builder()
                .email("oauth@example.com")
                .password("   ")
                .build();

        when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.changePassword(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("This account was created via social login");
    }

    /**
     * Verifies changePassword throws BadRequestException when current password is incorrect.
     */
    @Test
    @DisplayName("changePassword: throws BadRequestException when old password does not match")
    void changePassword_whenOldPasswordIncorrect_throwsBadRequestException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("wrongCurrentPassword")
                .newPassword("newValidPassword123")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedCurrentPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongCurrentPassword", "hashedCurrentPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Current password is incorrect.");
    }

    /**
     * Verifies changePassword updates password, deletes tokens, sends notification, and records audit entry.
     */
    @Test
    @DisplayName("changePassword: updates password, revokes tokens, sends notice, and logs audit")
    void changePassword_withValidRequest_updatesPasswordDeletesTokensSendsNoticeAndLogsAudit() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("correctOldPassword123")
                .newPassword("brandNewPassword456")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedOldPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctOldPassword123", "hashedOldPassword")).thenReturn(true);
        when(passwordEncoder.encode("brandNewPassword456")).thenReturn("hashedNewPassword");

        authService.changePassword(request);

        assertThat(user.getPassword()).isEqualTo("hashedNewPassword");
        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(emailUtil).sendPasswordChangedNotice("user@example.com");
        verify(auditLogService).logAction(AuditLogAction.Authentication.Password.Changed.VALUE,
                "Password changed for user: user@example.com", "user@example.com");
    }

    /**
     * Verifies changePassword catches email notification failure and completes successfully.
     */
    @Test
    @DisplayName("changePassword: catches email dispatch failure and completes audit")
    void changePassword_whenEmailNoticeFails_catchesExceptionAndCompletesSuccessfully() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "cred", List.of())
        );

        ChangePasswordRequestDTO request = ChangePasswordRequestDTO.builder()
                .oldPassword("correctOldPassword123")
                .newPassword("brandNewPassword456")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedOldPassword")
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctOldPassword123", "hashedOldPassword")).thenReturn(true);
        when(passwordEncoder.encode("brandNewPassword456")).thenReturn("hashedNewPassword");
        doThrow(new RuntimeException("Mail server down"))
                .when(emailUtil).sendPasswordChangedNotice(anyString());

        authService.changePassword(request);

        verify(auditLogService).logAction(AuditLogAction.Authentication.Password.Changed.VALUE,
                "Password changed for user: user@example.com", "user@example.com");
    }

    /**
     * Verifies registerUser rejects registration when role in DTO is null.
     */
    @Test
    @DisplayName("registerUser: throws BadRequestException when role is null")
    void registerUser_whenRoleIsNull_throwsBadRequestException() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("norole@example.com")
                .password("password123")
                .accountType(null)
                .build();

        when(userRepository.existsByEmail("norole@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.registerUser(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid account type. Allowed values are ARTISAN or CLIENT.");
    }

    /**
     * Verifies registerUser splits full name when firstName is blank.
     */
    @Test
    @DisplayName("registerUser: splits full name when firstName is blank")
    void registerUser_withBlankFirstNameAndPopulatedName_splitsName() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .firstName("   ")
                .name("Karim Bensalem")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });

        ProfileResponse response = authService.registerUser(dto);

        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getFirstName()).isEqualTo("Karim");
        assertThat(clientProfile.getLastName()).isEqualTo("Bensalem");
    }

    /**
     * Verifies registerUser does not split name when both firstName and name are null.
     */
    @Test
    @DisplayName("registerUser: preserves null names when firstName and name are null")
    void registerUser_withNullFirstNameAndNullName_keepsEmptyOrNullNames() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .firstName(null)
                .name(null)
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });

        ProfileResponse response = authService.registerUser(dto);

        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getFirstName()).isNull();
        assertThat(clientProfile.getLastName()).isNull();
    }

    /**
     * Verifies registerUser does not split name when firstName is null and name is blank.
     */
    @Test
    @DisplayName("registerUser: does not split name when firstName is null and name is blank")
    void registerUser_withNullFirstNameAndBlankName_keepsEmptyOrNullNames() {
        UserRegistrationDTO dto = UserRegistrationDTO.builder()
                .email("client@example.com")
                .password("rawPassword123")
                .firstName(null)
                .name("   ")
                .accountType(AccountRole.CLIENT)
                .build();

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("client-user-id");
            return user;
        });

        ProfileResponse response = authService.registerUser(dto);

        ClientProfileResponseDTO clientProfile = (ClientProfileResponseDTO) response;
        assertThat(clientProfile.getFirstName()).isNull();
        assertThat(clientProfile.getLastName()).isNull();
    }

    /**
     * Verifies login throws BadRequestException when DTO returns non-null blank identifier.
     */
    @Test
    @DisplayName("login: throws BadRequestException when identifier returned is blank string")
    void login_whenIdentifierIsBlankFromDTO_throwsBadRequestException() {
        LoginDTO mockDto = mock(LoginDTO.class);
        when(mockDto.getLoginIdentifier()).thenReturn("   ");

        assertThatThrownBy(() -> authService.login(mockDto, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email is required for login.");
    }

    /**
     * Verifies login increments attempts counter sequentially across attempts 1 through 4 without locking.
     */
    @Test
    @DisplayName("login: increments failed attempts counter sequentially without locking across attempts 1-4")
    void login_whenPasswordIncorrectMultipleTimes_incrementsAttemptsSequentially() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("wrongPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .failedLoginAttempts(2)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto, null))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    /**
     * Verifies login falls back to remote address when X-Forwarded-For header is present but blank.
     */
    @Test
    @DisplayName("login: falls back to remoteAddr when X-Forwarded-For header is blank")
    void login_withBlankXForwardedForHeader_fallsBackToRemoteAddr() {
        LoginDTO dto = LoginDTO.builder()
                .email("user@example.com")
                .password("correctPassword")
                .build();

        User user = User.builder()
                .email("user@example.com")
                .password("hashedPassword")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("10.20.30.40");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);
        when(jwtUtils.generateAccessToken("user@example.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshTokenForUser(user))
                .thenReturn(RefreshToken.builder().token("refresh-token").build());

        authService.login(dto, request);

        assertThat(user.getLastLoginIp()).isEqualTo("10.20.30.40");
    }

    /**
     * Verifies processOAuth2Success falls back to getName when sub attribute is null.
     */
    @Test
    @DisplayName("processOAuth2Success: falls back to getName when sub attribute is null")
    void processOAuth2Success_whenSubAttributeNull_fallsBackToOAuthUserName() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn(null);
        when(oAuth2User.getName()).thenReturn("google-name-null-sub");
        when(oAuth2User.getAttribute("email")).thenReturn("existing@example.com");

        User existingUser = User.builder()
                .email("existing@example.com")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        OAuthIdentity identity = OAuthIdentity.builder()
                .user(existingUser)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-name-null-sub")
                .build();

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-name-null-sub"))
                .thenReturn(Optional.of(identity));
        when(jwtUtils.generateAccessToken("existing@example.com")).thenReturn("access-token-oauth");
        when(refreshTokenService.createRefreshTokenForUser(existingUser))
                .thenReturn(RefreshToken.builder().token("refresh-token-oauth").build());

        JwtResponseDTO response = authService.processOAuth2Success(oAuth2User, null, null);

        assertThat(response.getAccessToken()).isEqualTo("access-token-oauth");
    }

    /**
     * Verifies processOAuth2Success throws BadRequestException when email from OAuth provider is blank.
     */
    @Test
    @DisplayName("processOAuth2Success: throws BadRequestException when email from OAuth provider is blank")
    void processOAuth2Success_whenEmailBlankFromOAuthUser_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-blank-email");
        when(oAuth2User.getAttribute("email")).thenReturn("   ");

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OAuth provider did not return an email address.");
    }

    /**
     * Verifies processOAuth2Success throws BadRequestException when intentRole is blank.
     */
    @Test
    @DisplayName("processOAuth2Success: throws BadRequestException when intentRole is blank string")
    void processOAuth2Success_whenNewUserAndIntentRoleBlank_throwsBadRequestException() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("sub")).thenReturn("google-sub-intent-blank");
        when(oAuth2User.getAttribute("email")).thenReturn("brandnew@example.com");

        when(oauthIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-intent-blank"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("brandnew@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.processOAuth2Success(oAuth2User, "   ", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("OAuth registration intent not found or expired.");
    }

    @Test
    @DisplayName("deleteCurrentAccount: throws UnauthorizedException when unauthenticated")
    void deleteCurrentAccount_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest("any", null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User is not authenticated.");
    }

    @Test
    @DisplayName("deleteCurrentAccount: throws UnauthorizedException when user not found in DB")
    void deleteCurrentAccount_whenUserNotFound_throwsUnauthorizedException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ghost@example.com", "cred", List.of())
        );
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest("any", null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User is not authenticated.");
    }

    @Test
    @DisplayName("deleteCurrentAccount: password user fails when password is invalid or missing")
    void deleteCurrentAccount_passwordUser_whenPasswordInvalid_throwsUnauthorizedException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pwduser@example.com", "cred", List.of())
        );
        User user = User.builder()
                .email("pwduser@example.com")
                .password("$2a$10$hashedPassword")
                .build();
        user.setId("99");
        when(userRepository.findByEmail("pwduser@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "$2a$10$hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest("wrongpass", null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Valid password is required.");

        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest(null, null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Valid password is required.");
    }

    @Test
    @DisplayName("deleteCurrentAccount: password user succeeds when password matches")
    void deleteCurrentAccount_passwordUser_whenPasswordMatches_anonymizesAndSavesUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pwduser@example.com", "cred", List.of())
        );
        User user = User.builder()
                .email("pwduser@example.com")
                .password("$2a$10$hashedPassword")
                .firstName("John")
                .lastName("Doe")
                .status(AccountStatus.ACTIVE)
                .build();
        user.setId("42");
        when(userRepository.findByEmail("pwduser@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPass", "$2a$10$hashedPassword")).thenReturn(true);
        when(oauthIdentityRepository.findByUser(user)).thenReturn(List.of());

        authService.deleteCurrentAccount(new DeleteAccountRequest("correctPass", null));

        verify(refreshTokenService).deleteByUser(user);
        verify(userRepository).save(user);
        assertThat(user.getStatus()).isEqualTo(AccountStatus.DELETED);
        assertThat(user.getEmail()).isEqualTo("deleted+42@deleted.souklab.invalid");
        assertThat(user.getPassword()).isNull();
        assertThat(user.getFirstName()).isEqualTo("Deleted");
        assertThat(user.getLastName()).isEqualTo("User");
        assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("deleteCurrentAccount: oauth user fails when OAuth login is stale or confirmation is missing")
    void deleteCurrentAccount_oauthUser_whenStaleOrUnconfirmed_throwsUnauthorizedException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("oauthuser@example.com", "cred", List.of())
        );
        User user = User.builder()
                .email("oauthuser@example.com")
                .password(null)
                .lastOAuthLoginAt(LocalDateTime.now(fixedClock).minusMinutes(20))
                .build();
        user.setId("101");
        when(userRepository.findByEmail("oauthuser@example.com")).thenReturn(Optional.of(user));

        // Case 1: Stale OAuth login (older than 10 minutes)
        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest(null, true)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Recent OAuth confirmation is required.");

        // Case 2: Recent OAuth login but oauthConfirmed is false / null
        user.setLastOAuthLoginAt(LocalDateTime.now(fixedClock).minusMinutes(2));
        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest(null, false)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Recent OAuth confirmation is required.");

        assertThatThrownBy(() -> authService.deleteCurrentAccount(new DeleteAccountRequest(null, null)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Recent OAuth confirmation is required.");
    }

    @Test
    @DisplayName("deleteCurrentAccount: oauth user succeeds when recent login and confirmed")
    void deleteCurrentAccount_oauthUser_whenRecentAndConfirmed_anonymizesAndSavesUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("oauthuser@example.com", "cred", List.of())
        );
        User user = User.builder()
                .email("oauthuser@example.com")
                .password(null)
                .lastOAuthLoginAt(LocalDateTime.now(fixedClock).minusMinutes(3))
                .status(AccountStatus.ACTIVE)
                .build();
        user.setId("102");
        OAuthIdentity identity = OAuthIdentity.builder()
                .user(user)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-55")
                .email("oauthuser@example.com")
                .build();
        identity.setId("55");

        when(userRepository.findByEmail("oauthuser@example.com")).thenReturn(Optional.of(user));
        when(oauthIdentityRepository.findByUser(user)).thenReturn(List.of(identity));

        authService.deleteCurrentAccount(new DeleteAccountRequest(null, true));

        verify(refreshTokenService).deleteByUser(user);
        verify(oauthIdentityRepository).save(identity);
        assertThat(identity.getProviderUserId()).isEqualTo("deleted-55");
        assertThat(identity.getEmail()).isNull();

        verify(userRepository).save(user);
        assertThat(user.getStatus()).isEqualTo(AccountStatus.DELETED);
        assertThat(user.getEmail()).isEqualTo("deleted+102@deleted.souklab.invalid");
        assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("DeleteAccountRequest: Jackson deserializes oauthConfirmed correctly into providerConfirmed")
    void deleteAccountRequest_jacksonDeserialization_mapsOauthConfirmed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"oauthConfirmed\": true, \"password\": \"mySecret\"}";

        DeleteAccountRequest request = mapper.readValue(json, DeleteAccountRequest.class);

        assertThat(request).isNotNull();
        assertThat(request.password()).isEqualTo("mySecret");
        assertThat(request.getOauthConfirmed()).isTrue();
        assertThat(request.providerConfirmed()).isTrue();
    }
}

