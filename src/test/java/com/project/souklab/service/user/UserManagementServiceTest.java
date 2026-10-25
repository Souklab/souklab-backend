package com.project.souklab.service.user;

import com.project.souklab.config.AdminProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.auth.UserResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link UserManagementService}.
 * Validates user approvals, indefinite bans, temporary timeouts, paginated retrieval,
 * search filtering, and DTO transformation branches.
 */
@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T12:00:00Z");
    private static final ZoneOffset ZONE = ZoneOffset.UTC;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private NotificationService notificationService;

    private Clock clock;
    private UserManagementService userManagementService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZONE);
        AdminProperties properties = new AdminProperties();
        properties.setDefaultBanReason("Account banned by administrator");
        properties.setDefaultTimeoutReason("Account timed out by administrator");
        userManagementService = new UserManagementService(
                userRepository,
                artisanRepository,
                auditLogService,
                refreshTokenService,
                notificationService,
                clock,
                properties
        );
    }

    /**
     * Verifies that getAllUsers calls searchUsers when search query is provided and non-blank.
     */
    @Test
    @DisplayName("getAllUsers: queries searchUsers when search query is non-blank")
    void getAllUsers_withNonBlankSearch_shouldCallSearchUsersAndMapResults() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-1", "karim@example.com", "Karim", "Bensaid", AccountStatus.ACTIVE);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.searchUsers("karim", pageable)).thenReturn(page);

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers("karim", pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo("u-1");
        assertThat(response.getContent().get(0).getEmail()).isEqualTo("karim@example.com");
        assertThat(response.getContent().get(0).getName()).isEqualTo("Karim Bensaid");
        verify(userRepository).searchUsers("karim", pageable);
        verify(userRepository, never()).findAll(pageable);
    }

    /**
     * Verifies that getAllUsers calls findAll when search query is null.
     */
    @Test
    @DisplayName("getAllUsers: queries findAll when search query is null")
    void getAllUsers_withNullSearch_shouldCallFindAllAndMapResults() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-2", "amine@example.com", "Amine", "Khelil", AccountStatus.ACTIVE);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.findAll(pageable)).thenReturn(page);

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getEmail()).isEqualTo("amine@example.com");
        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).searchUsers(any(), any());
    }

    /**
     * Verifies that getAllUsers calls findAll when search query is an empty string.
     */
    @Test
    @DisplayName("getAllUsers: queries findAll when search query is empty")
    void getAllUsers_withEmptySearch_shouldCallFindAllAndMapResults() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-3", "user@example.com", "User", "Three", AccountStatus.ACTIVE);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.findAll(pageable)).thenReturn(page);

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers("", pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).searchUsers(any(), any());
    }

    /**
     * Verifies that getAllUsers calls findAll when search query consists only of whitespace.
     */
    @Test
    @DisplayName("getAllUsers: queries findAll when search query is whitespace")
    void getAllUsers_withWhitespaceSearch_shouldCallFindAllAndMapResults() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-4", "user4@example.com", "User", "Four", AccountStatus.ACTIVE);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.findAll(pageable)).thenReturn(page);

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers("   ", pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).searchUsers(any(), any());
    }

    /**
     * Verifies that getPendingUsers calls findByStatus with AccountStatus.PENDING.
     */
    @Test
    @DisplayName("getPendingUsers: queries userRepository by PENDING status")
    void getPendingUsers_shouldQueryPendingStatusAndMapResults() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-pending", "pending@example.com", "Pending", "User", AccountStatus.PENDING);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);

        when(userRepository.findByStatusAndDeletedAtIsNull(AccountStatus.PENDING, pageable)).thenReturn(page);

        PaginatedResponse<UserResponseDTO> response = userManagementService.getPendingUsers(pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getStatus()).isEqualTo(AccountStatus.PENDING);
        verify(userRepository).findByStatusAndDeletedAtIsNull(AccountStatus.PENDING, pageable);
    }

    /**
     * Verifies that approveUser throws ResourceNotFoundException when user id is not found.
     */
    @Test
    @DisplayName("approveUser: throws ResourceNotFoundException when user does not exist")
    void approveUser_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("missing-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.approveUser("missing-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: missing-id");

        verify(userRepository, never()).save(any());
        verify(artisanRepository, never()).save(any());
        verify(auditLogService, never()).logAction(any(), any());
        verify(notificationService, never()).createForUser(any(), any(), any(), any());
    }

    /**
     * Verifies that approveUser throws BadRequestException when user status is ACTIVE.
     */
    @Test
    @DisplayName("approveUser: throws BadRequestException when user status is already ACTIVE")
    void approveUser_whenUserAlreadyActive_shouldThrowBadRequestException() {
        User user = createUser("u-active", "active@example.com", "Active", "User", AccountStatus.ACTIVE);
        when(userRepository.findById("u-active")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userManagementService.approveUser("u-active"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User is not pending approval. Current status: ACTIVE");

        verify(userRepository, never()).save(any());
        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies that approveUser throws BadRequestException when user status is SUSPENDED.
     */
    @Test
    @DisplayName("approveUser: throws BadRequestException when user status is SUSPENDED")
    void approveUser_whenUserSuspended_shouldThrowBadRequestException() {
        User user = createUser("u-suspended", "suspended@example.com", "Suspended", "User", AccountStatus.SUSPENDED);
        when(userRepository.findById("u-suspended")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userManagementService.approveUser("u-suspended"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User is not pending approval. Current status: SUSPENDED");

        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies that approveUser updates status to ACTIVE, saves user, audits, and notifies when not an artisan.
     */
    @Test
    @DisplayName("approveUser: successfully approves standard pending user when not an artisan")
    void approveUser_whenPendingAndNotArtisan_shouldActivateUserAndSendNotification() {
        User user = createUser("u-pending", "pending@example.com", "Pending", "Client", AccountStatus.PENDING);
        when(userRepository.findById("u-pending")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("u-pending")).thenReturn(Optional.empty());

        userManagementService.approveUser("u-pending");

        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository).save(user);
        verify(artisanRepository, never()).save(any());
        verify(auditLogService).logAction(AuditLogAction.User.APPROVED, "Approved user ID: u-pending");
        verify(notificationService).createForUser(
                user,
                "Your account has been approved and is now active!",
                NotificationType.Account.VALIDATED,
                "u-pending"
        );
    }

    /**
     * Verifies that approveUser activates user and sets artisan verified=true when user is an artisan.
     */
    @Test
    @DisplayName("approveUser: activates user and marks artisan verified when user has an artisan profile")
    void approveUser_whenPendingAndIsArtisan_shouldActivateUserAndVerifyArtisan() {
        User user = createUser("u-artisan", "artisan@example.com", "Artisan", "One", AccountStatus.PENDING);
        Artisan artisan = Artisan.builder()
                .id("u-artisan")
                .user(user)
                .isVerified(false)
                .build();

        when(userRepository.findById("u-artisan")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("u-artisan")).thenReturn(Optional.of(artisan));

        userManagementService.approveUser("u-artisan");

        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(artisan.isVerified()).isTrue();
        verify(userRepository).save(user);
        verify(artisanRepository).save(artisan);
        verify(auditLogService).logAction(AuditLogAction.User.APPROVED, "Approved user ID: u-artisan");
        verify(notificationService).createForUser(
                user,
                "Your account has been approved and is now active!",
                NotificationType.Account.VALIDATED,
                "u-artisan"
        );
    }

    /**
     * Verifies that banUser throws ResourceNotFoundException when user does not exist.
     */
    @Test
    @DisplayName("banUser: throws ResourceNotFoundException when user is not found")
    void banUser_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.banUser("non-existent", "Spamming"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: non-existent");

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).deleteByUser(any());
    }

    /**
     * Verifies that banUser sets SUSPENDED status, 100-year ban, reason, revokes tokens, logs audit, and sends notification.
     */
    @Test
    @DisplayName("banUser: indefinitely suspends user, records explicit reason, and invalidates refresh tokens")
    void banUser_whenUserFoundWithExplicitReason_shouldSuspendIndefinitelyAndRevokeTokens() {
        User user = createUser("u-bad", "bad@example.com", "Bad", "Actor", AccountStatus.ACTIVE);
        when(userRepository.findById("u-bad")).thenReturn(Optional.of(user));

        userManagementService.banUser("u-bad", "Repeated fraudulent transactions");

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(user.getBanReason()).isEqualTo("Repeated fraudulent transactions");
        assertThat(user.getBannedUntil()).isNull();

        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(auditLogService).logAction(AuditLogAction.User.BANNED, "Banned user ID: u-bad. Reason: Repeated fraudulent transactions");
        verify(notificationService).createForUser(
                user,
                "Your account has been permanently suspended. Reason: Repeated fraudulent transactions",
                NotificationType.Account.SUSPENDED,
                "u-bad"
        );
    }

    /**
     * Verifies that banUser falls back to default reason when provided reason is null.
     */
    @Test
    @DisplayName("banUser: applies default ban reason when administrative reason is null")
    void banUser_whenUserFoundWithNullReason_shouldUseDefaultReasonAndSuspendIndefinitely() {
        User user = createUser("u-bad2", "bad2@example.com", "Bad", "Two", AccountStatus.ACTIVE);
        when(userRepository.findById("u-bad2")).thenReturn(Optional.of(user));

        userManagementService.banUser("u-bad2", null);

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(user.getBanReason()).isEqualTo("Account banned by administrator");
        assertThat(user.getBannedUntil()).isNull();

        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(auditLogService).logAction(AuditLogAction.User.BANNED, "Banned user ID: u-bad2. Reason: null");
        verify(notificationService).createForUser(
                user,
                "Your account has been permanently suspended. Reason: null",
                NotificationType.Account.SUSPENDED,
                "u-bad2"
        );
    }

    @Test
    @DisplayName("banUser: throws BadRequestException when administrator attempts to ban their own account")
    void banUser_whenAdminBansSelf_shouldThrowBadRequestException() {
        User admin = createUser("admin-1", "admin@example.com", "Admin", "User", AccountStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin@example.com", "credentials", "ROLE_ADMIN"));

        try {
            assertThatThrownBy(() -> userManagementService.banUser("admin-1", "Self ban"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Administrators cannot ban or timeout their own account.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("timeoutUser: throws BadRequestException when administrator attempts to timeout their own account")
    void timeoutUser_whenAdminTimeoutsSelf_shouldThrowBadRequestException() {
        User admin = createUser("admin-1", "admin@example.com", "Admin", "User", AccountStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin@example.com", "credentials", "ROLE_ADMIN"));

        try {
            assertThatThrownBy(() -> userManagementService.timeoutUser("admin-1", 30, "Self timeout"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Administrators cannot ban or timeout their own account.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Verifies that timeoutUser throws BadRequestException when minutes is zero.
     */
    @Test
    @DisplayName("timeoutUser: throws BadRequestException when minutes is 0")
    void timeoutUser_whenMinutesIsZero_shouldThrowBadRequestException() {
        assertThatThrownBy(() -> userManagementService.timeoutUser("u-1", 0, "Cooling off"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Timeout duration must be greater than 0 minutes");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies that timeoutUser throws BadRequestException when minutes is negative.
     */
    @Test
    @DisplayName("timeoutUser: throws BadRequestException when minutes is negative")
    void timeoutUser_whenMinutesIsNegative_shouldThrowBadRequestException() {
        assertThatThrownBy(() -> userManagementService.timeoutUser("u-1", -15, "Cooling off"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Timeout duration must be greater than 0 minutes");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies that timeoutUser throws ResourceNotFoundException when user is not found.
     */
    @Test
    @DisplayName("timeoutUser: throws ResourceNotFoundException when user does not exist")
    void timeoutUser_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("missing-u")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.timeoutUser("missing-u", 60, "Harassment in comments"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: missing-u");

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).deleteByUser(any());
    }

    /**
     * Verifies that timeoutUser suspends user for specified duration, saves reason, revokes tokens, logs audit, and notifies.
     */
    @Test
    @DisplayName("timeoutUser: suspends user for duration, records explicit reason, and invalidates refresh tokens")
    void timeoutUser_whenUserFoundWithExplicitReason_shouldSuspendForDurationAndRevokeTokens() {
        User user = createUser("u-timeout", "timeout@example.com", "Time", "Out", AccountStatus.ACTIVE);
        when(userRepository.findById("u-timeout")).thenReturn(Optional.of(user));

        userManagementService.timeoutUser("u-timeout", 120, "Spamming chat");

        LocalDateTime expectedBannedUntil = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusMinutes(120);

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(user.getBanReason()).isEqualTo("Spamming chat");
        assertThat(user.getBannedUntil()).isEqualTo(expectedBannedUntil);

        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(auditLogService).logAction(
                AuditLogAction.User.Timeout.VALUE,
                "Timed out user ID: u-timeout for 120 minutes. Reason: Spamming chat"
        );
        verify(notificationService).createForUser(
                user,
                "Your account has been timed out for 120 minutes. Reason: Spamming chat",
                NotificationType.Account.SUSPENDED,
                "u-timeout"
        );
    }

    /**
     * Verifies that timeoutUser falls back to default timeout reason when provided reason is null.
     */
    @Test
    @DisplayName("timeoutUser: applies default timeout reason when reason parameter is null")
    void timeoutUser_whenUserFoundWithNullReason_shouldUseDefaultReasonAndSuspendForDuration() {
        User user = createUser("u-timeout2", "timeout2@example.com", "Time", "Out2", AccountStatus.ACTIVE);
        when(userRepository.findById("u-timeout2")).thenReturn(Optional.of(user));

        userManagementService.timeoutUser("u-timeout2", 30, null);

        LocalDateTime expectedBannedUntil = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusMinutes(30);

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(user.getBanReason()).isEqualTo("Account timed out by administrator");
        assertThat(user.getBannedUntil()).isEqualTo(expectedBannedUntil);

        verify(userRepository).save(user);
        verify(refreshTokenService).deleteByUser(user);
        verify(auditLogService).logAction(
                AuditLogAction.User.Timeout.VALUE,
                "Timed out user ID: u-timeout2 for 30 minutes. Reason: null"
        );
        verify(notificationService).createForUser(
                user,
                "Your account has been timed out for 30 minutes. Reason: null",
                NotificationType.Account.SUSPENDED,
                "u-timeout2"
        );
    }

    /**
     * Verifies that unbanUser restores ACTIVE status, clears bannedUntil and banReason, logs audit, and sends notification.
     */
    @Test
    @DisplayName("unbanUser: reinstates suspended user to ACTIVE and clears timeout restrictions")
    void unbanUser_whenUserIsSuspended_shouldRestoreActiveAndClearRestrictions() {
        User user = createUser("u-unban", "unban@example.com", "Suspended", "User", AccountStatus.SUSPENDED);
        user.setBannedUntil(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusDays(5));
        user.setBanReason("Terms violation");
        when(userRepository.findById("u-unban")).thenReturn(Optional.of(user));

        userManagementService.unbanUser("u-unban");

        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.getBannedUntil()).isNull();
        assertThat(user.getBanReason()).isNull();

        verify(userRepository).save(user);
        verify(auditLogService).logAction(AuditLogAction.User.UNBANNED, "Reinstated user ID: u-unban");
        verify(notificationService).createForUser(
                user,
                "Your account suspension has been lifted and your access has been restored.",
                NotificationType.Account.REINSTATED,
                "u-unban"
        );
    }

    /**
     * Verifies that unbanUser throws ConflictException when user is not in SUSPENDED status.
     */
    @Test
    @DisplayName("unbanUser: throws ConflictException when target user is not suspended")
    void unbanUser_whenUserIsNotSuspended_shouldThrowConflictException() {
        User user = createUser("u-active", "active@example.com", "Active", "User", AccountStatus.ACTIVE);
        when(userRepository.findById("u-active")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userManagementService.unbanUser("u-active"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("User is not suspended. Current status: ACTIVE");

        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).logAction(any(), any());
        verify(notificationService, never()).createForUser(any(), any(), any(), any());
    }

    /**
     * Verifies that unbanUser throws ResourceNotFoundException when user does not exist.
     */
    @Test
    @DisplayName("unbanUser: throws ResourceNotFoundException when user id is not found")
    void unbanUser_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findById("u-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userManagementService.unbanUser("u-missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found with id: u-missing");

        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies mapToDTO behavior when user has no permissions assigned.
     */
    @Test
    @DisplayName("mapToDTO: sets primaryPermission to null and permissions to empty set when user has no permissions")
    void mapToDTO_whenUserHasNoRoles_primaryPermissionShouldBeNull() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-no-role", "norole@example.com", "No", "AuthorizationPermission", AccountStatus.ACTIVE);
        user.setPermissions(Collections.emptySet());

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getPermissions()).isEmpty();
    }

    /**
     * Verifies mapToDTO behavior when user has permissions assigned.
     */
    @Test
    @DisplayName("mapToDTO: populates primaryPermission and role set when permissions are present")
    void mapToDTO_whenUserHasRoles_primaryPermissionAndRolesShouldBePopulated() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-permissions", "permissions@example.com", "With", "Roles", AccountStatus.ACTIVE);
        AuthorizationPermission artisanContent = new AuthorizationPermission();
        artisanContent.setPermissionKey(Permission.Artisan.CONTENT.value());
        user.setPermissions(Set.of(artisanContent));

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getPermissions()).containsExactly(Permission.Artisan.CONTENT);
    }

    /**
     * Verifies mapToDTO sets name to combined firstName and lastName.
     */
    @Test
    @DisplayName("mapToDTO: sets name to combined firstName and lastName when both are present")
    void mapToDTO_whenFirstNameAndLastNamePresent_nameShouldBeCombined() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-name1", "name1@example.com", "Fatima", "Zahra", AccountStatus.ACTIVE);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getName()).isEqualTo("Fatima Zahra");
    }

    /**
     * Verifies mapToDTO formats name when only firstName is present.
     */
    @Test
    @DisplayName("mapToDTO: sets name to trimmed firstName when lastName is null")
    void mapToDTO_whenOnlyFirstNamePresent_nameShouldBeFirstName() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-name2", "name2@example.com", "Fatima", null, AccountStatus.ACTIVE);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getName()).isEqualTo("Fatima");
    }

    /**
     * Verifies mapToDTO formats name when only lastName is present.
     */
    @Test
    @DisplayName("mapToDTO: sets name to trimmed lastName when firstName is null")
    void mapToDTO_whenOnlyLastNamePresent_nameShouldBeLastName() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-name3", "name3@example.com", null, "Zahra", AccountStatus.ACTIVE);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getName()).isEqualTo("Zahra");
    }

    /**
     * Verifies mapToDTO falls back to email when both firstName and lastName are null.
     */
    @Test
    @DisplayName("mapToDTO: falls back to email as name when both firstName and lastName are null")
    void mapToDTO_whenFirstAndLastNameNull_nameShouldFallBackToEmail() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-name4", "fallback@example.com", null, null, AccountStatus.ACTIVE);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getName()).isEqualTo("fallback@example.com");
    }

    /**
     * Verifies mapToDTO falls back to email when both firstName and lastName are blank strings.
     */
    @Test
    @DisplayName("mapToDTO: falls back to email as name when firstName and lastName are blank")
    void mapToDTO_whenFirstAndLastNameBlank_nameShouldFallBackToEmail() {
        Pageable pageable = PageRequest.of(0, 10);
        User user = createUser("u-name5", "blank@example.com", "   ", "   ", AccountStatus.ACTIVE);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getName()).isEqualTo("blank@example.com");
    }

    /**
     * Verifies that mapToDTO copies all scalar fields including phone, avatarUrl, timestamps, and verification status.
     */
    @Test
    @DisplayName("mapToDTO: copies all scalar metadata fields correctly into DTO")
    void mapToDTO_allScalarFields_shouldBeMappedCorrectly() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDateTime lastLogin = LocalDateTime.of(2026, 9, 1, 8, 30);
        LocalDateTime bannedUntil = LocalDateTime.of(2026, 9, 10, 12, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 9, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 2, 14, 0);

        AuthorizationPermission profileRead = new AuthorizationPermission();
        profileRead.setPermissionKey(Permission.Profile.READ.value());

        User user = User.builder()
                .email("detailed@example.com")
                .firstName("Detailed")
                .lastName("User")
                .phone("+213555123456")
                .avatarUrl("https://example.com/avatar.png")
                .status(AccountStatus.SUSPENDED)
                .emailVerified(true)
                .emailVerifiedAt(verifiedAt)
                .bannedUntil(bannedUntil)
                .banReason("Temporary suspension")
                .lastLoginAt(lastLogin)
                .permissions(Set.of(profileRead))
                .build();
        user.setId("u-detailed");
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(updatedAt);

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getId()).isEqualTo("u-detailed");
        assertThat(dto.getEmail()).isEqualTo("detailed@example.com");
        assertThat(dto.getFirstName()).isEqualTo("Detailed");
        assertThat(dto.getLastName()).isEqualTo("User");
        assertThat(dto.getName()).isEqualTo("Detailed User");
        assertThat(dto.getPhone()).isEqualTo("+213555123456");
        assertThat(dto.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(dto.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(dto.isEmailVerified()).isTrue();
        assertThat(dto.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(dto.getPermissions()).containsExactly(Permission.Profile.READ);
        assertThat(dto.getBannedUntil()).isEqualTo(bannedUntil);
        assertThat(dto.getBanReason()).isEqualTo("Temporary suspension");
        assertThat(dto.getLastLoginAt()).isEqualTo(lastLogin);
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dto.getUpdatedAt()).isEqualTo(updatedAt);
    }

    /**
     * Verifies that getAllUsers returns effective ACTIVE status and null ban fields for an expired-timeout user
     * without triggering any database write.
     */
    @Test
    @DisplayName("getAllUsers: returns effective ACTIVE status with null ban fields when timeout has expired, without saving")
    void getAllUsers_whenTimeoutExpired_returnsEffectiveActiveStatusWithoutDbWrite() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDateTime expiredBannedUntil = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).minusMinutes(15);

        User user = createUser("u-expired", "expired@example.com", "Expired", "Timeout", AccountStatus.SUSPENDED);
        user.setBannedUntil(expiredBannedUntil);
        user.setBanReason("Spam activity");

        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers(null, pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(dto.getBannedUntil()).isNull();
        assertThat(dto.getBanReason()).isNull();

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(user.getBannedUntil()).isEqualTo(expiredBannedUntil);
        assertThat(user.getBanReason()).isEqualTo("Spam activity");

        verify(userRepository, never()).save(any());
    }

    /**
     * Verifies that searchUsers also returns effective ACTIVE status and null ban fields for an expired-timeout user
     * without triggering any database write.
     */
    @Test
    @DisplayName("getAllUsers with search: returns effective ACTIVE status with null ban fields when timeout has expired, without saving")
    void getAllUsers_withSearchWhenTimeoutExpired_returnsEffectiveActiveStatusWithoutDbWrite() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDateTime expiredBannedUntil = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).minusMinutes(5);

        User user = createUser("u-search-exp", "search.exp@example.com", "Search", "Expired", AccountStatus.SUSPENDED);
        user.setBannedUntil(expiredBannedUntil);
        user.setBanReason("Automated flag");

        when(userRepository.searchUsers("search", pageable)).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        PaginatedResponse<UserResponseDTO> response = userManagementService.getAllUsers("search", pageable);

        UserResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(dto.getBannedUntil()).isNull();
        assertThat(dto.getBanReason()).isNull();

        assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("getUserById: populates isPremium, isTeacher, and isValidated from artisan profile")
    void getUserById_populatesArtisanProfileFlags() {
        User user = createUser("u-artisan", "artisan@example.com", "Ahmed", "Benali", AccountStatus.ACTIVE);
        Artisan artisan = Artisan.builder()
                .isPremium(true)
                .isTeacher(true)
                .isVerified(true)
                .build();
        user.setArtisan(artisan);

        when(userRepository.findById("u-artisan")).thenReturn(Optional.of(user));

        UserResponseDTO dto = userManagementService.getUserById("u-artisan");

        assertThat(dto.isPremium()).isTrue();
        assertThat(dto.isTeacher()).isTrue();
        assertThat(dto.isValidated()).isTrue();
    }

    private User createUser(String id, String email, String firstName, String lastName, AccountStatus status) {
        User user = User.builder()
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .status(status)
                .permissions(new HashSet<>())
                .build();
        user.setId(id);
        return user;
    }
}
