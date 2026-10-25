package com.project.souklab.service.user;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.auth.UserResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.Client;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.security.Permission;
import com.project.souklab.event.DomainEventPublisher;
import com.project.souklab.event.user.UserStatusChangedEvent;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.security.RefreshTokenService;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.souklab.config.AdminProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private static final String ERROR_USER_NOT_FOUND_PREFIX = "User not found with id: ";

    private final UserRepository userRepository;
    private final ArtisanRepository artisanRepository;
    private final AuditLogService auditLogService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final AdminProperties adminProperties;
    private ActivityEventService activityEventService;
    private DomainEventPublisher eventPublisher;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Autowired(required = false)
    void setEventPublisher(DomainEventPublisher eventPublisher) { this.eventPublisher = eventPublisher; }

    /**
     * Retrieves a paginated list of all users in the system.
     *
     * @param search optional search query for filtering by email or name
     * @param pageable the pagination parameters specifying page size, number, and sorting
     * @return a paginated response containing a list of all users as UserResponseDTOs
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<UserResponseDTO> getAllUsers(String search, Pageable pageable) {
        Page<User> page;
        if (search != null && !search.isBlank()) {
            page = userRepository.searchUsers(search, pageable);
        } else {
            page = userRepository.findAll(pageable);
        }
        return PaginatedResponse.from(page.map(this::mapToDTO));
    }

    /**
     * Retrieves a paginated list of users whose registrations are currently pending approval.
     * Typically used by admins to vet new artisan accounts before activating them.
     *
     * @param pageable the pagination parameters
     * @return a paginated response containing the list of pending UserResponseDTOs
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<UserResponseDTO> getPendingUsers(Pageable pageable) {
        Page<UserResponseDTO> page = userRepository.findByStatusAndDeletedAtIsNull(AccountStatus.PENDING, pageable)
                .map(this::mapToDTO);
        return PaginatedResponse.from(page);
    }

    /**
     * Retrieves full user details by user ID for administrative inspection.
     *
     * @param userId the unique identifier of the user
     * @return UserResponseDTO containing user profile and permissions
     * @throws ResourceNotFoundException if user is not found
     */
    @Transactional(readOnly = true)
    public UserResponseDTO getUserById(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + userId));
        return mapToDTO(user);
    }

    /**
     * Approves a user's pending registration, transitioning their status from PENDING to ACTIVE.
     * Logs the action and triggers an approval notification to the user.
     *
     * @param userId the unique identifier of the user to approve
     * @throws ResourceNotFoundException if the user is not found
     * @throws BadRequestException if the user is not in a PENDING status
     */
    @Transactional
    public void approveUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + userId));

        if (user.getStatus() != AccountStatus.PENDING) {
            throw new BadRequestException("User is not pending approval. Current status: " + user.getStatus());
        }

        user.setStatus(AccountStatus.ACTIVE);
        userRepository.save(user);

        artisanRepository.findById(userId).ifPresentOrElse(artisan -> {
            artisan.setVerified(true);
            artisanRepository.save(artisan);
        }, () -> {
            Artisan artisan = Artisan.builder()
                    .user(user)
                    .isVerified(true)
                    .build();
            user.setArtisan(artisan);
            userRepository.saveAndFlush(user);
        });

        auditLogService.logAction(AuditLogAction.User.APPROVED, "Approved user ID: " + userId);
        recordModeration(AnalyticsEvent.User.APPROVED, user, Map.of());
        notificationService.createForUser(user, "Your account has been approved and is now active!", NotificationType.Account.VALIDATED, user.getId());
        if (eventPublisher != null) {
            eventPublisher.publish(UserStatusChangedEvent.of(user.getId(), user.getEmail(), AccountStatus.PENDING, AccountStatus.ACTIVE, "User approved by administrator"));
        }
    }

    /**
     * Permanently or indefinitely bans a user from the platform.
     * Immediately revokes active refresh tokens for the user to force complete logout.
     *
     * @param userId the unique identifier of the user to ban
     * @param reason the administrative justification for the ban
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public void banUser(String userId, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + userId));

        validateNotSelfTargeting(user);

        user.setStatus(AccountStatus.SUSPENDED);
        user.setBanReason(reason != null ? reason : adminProperties.getDefaultBanReason());
        user.setBannedUntil(null);

        userRepository.save(user);

        refreshTokenService.deleteByUser(user);

        auditLogService.logAction(AuditLogAction.User.BANNED, "Banned user ID: " + userId + ". Reason: " + reason);
        recordModeration(AnalyticsEvent.User.SUSPENDED, user, Map.of(AnalyticsMetadata.Moderation.Reason.PRESENT, reason != null && !reason.isBlank()));
        notificationService.createForUser(user, "Your account has been permanently suspended. Reason: " + reason, NotificationType.Account.SUSPENDED, user.getId());
        if (eventPublisher != null) {
            eventPublisher.publish(UserStatusChangedEvent.of(user.getId(), user.getEmail(), AccountStatus.ACTIVE, AccountStatus.SUSPENDED, reason));
        }
    }

    /**
     * Temporarily suspends (timeouts) a user for a designated duration in minutes.
     * Immediately revokes active refresh tokens to force the user to log out during the timeout.
     *
     * @param userId the unique identifier of the user to timeout
     * @param minutes the duration of the suspension in minutes
     * @param reason the administrative justification for the timeout
     * @throws ResourceNotFoundException if the user is not found
     * @throws BadRequestException if minutes is non-positive
     */
    @Transactional
    public void timeoutUser(String userId, int minutes, String reason) {
        if (minutes <= 0) {
            throw new BadRequestException("Timeout duration must be greater than 0 minutes");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + userId));

        validateNotSelfTargeting(user);

        user.setStatus(AccountStatus.SUSPENDED);
        user.setBanReason(reason != null ? reason : adminProperties.getDefaultTimeoutReason());
        user.setBannedUntil(LocalDateTime.now(clock).plusMinutes(minutes));
        userRepository.save(user);

        refreshTokenService.deleteByUser(user);

        auditLogService.logAction(AuditLogAction.User.Timeout.VALUE, "Timed out user ID: " + userId + " for " + minutes + " minutes. Reason: " + reason);
        recordModeration(AnalyticsEvent.User.Timeout.EVENT, user, Map.of(AnalyticsMetadata.Moderation.Duration.MINUTES, minutes));
        notificationService.createForUser(user, "Your account has been timed out for " + minutes + " minutes. Reason: " + reason, NotificationType.Account.SUSPENDED, user.getId());
        if (eventPublisher != null) {
            eventPublisher.publish(UserStatusChangedEvent.of(user.getId(), user.getEmail(), AccountStatus.ACTIVE, AccountStatus.SUSPENDED, "Timed out for " + minutes + " minutes"));
        }
    }

    /**
     * Reinstates a suspended user, restoring their status to ACTIVE and clearing timeout restrictions.
     *
     * @param userId the unique identifier of the user to unban
     * @throws ResourceNotFoundException if the user is not found
     * @throws ConflictException if the user is not currently suspended
     */
    @Transactional
    public void unbanUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + userId));

        if (user.getStatus() != AccountStatus.SUSPENDED) {
            throw new ConflictException("User is not suspended. Current status: " + user.getStatus());
        }

        user.setStatus(AccountStatus.ACTIVE);
        user.setBannedUntil(null);
        user.setBanReason(null);
        userRepository.save(user);

        auditLogService.logAction(AuditLogAction.User.UNBANNED, "Reinstated user ID: " + userId);
        recordModeration(AnalyticsEvent.User.REINSTATED, user, Map.of());
        notificationService.createForUser(user, "Your account suspension has been lifted and your access has been restored.", NotificationType.Account.REINSTATED, user.getId());
        if (eventPublisher != null) {
            eventPublisher.publish(UserStatusChangedEvent.of(user.getId(), user.getEmail(), AccountStatus.SUSPENDED, AccountStatus.ACTIVE, "User reinstated"));
        }
    }

    private void validateNotSelfTargeting(User targetUser) {
        String currentAdminEmail = SecurityUtils.getCurrentUsername();
        if (currentAdminEmail != null && currentAdminEmail.equalsIgnoreCase(targetUser.getEmail())) {
            throw new BadRequestException("Administrators cannot ban or timeout their own account.");
        }
    }

    private void recordModeration(AnalyticsEvent.Type type, User user,
                                  Map<? extends AnalyticsMetadata.Key, ?> metadata) {
        if (activityEventService != null) activityEventService.record(type, user.getId(), user.getId(), metadata);
    }

    private UserResponseDTO mapToDTO(User user) {
        Set<Permission> roleNames = user.getPermissions().stream()
                .map(AuthorizationPermission::getPermissionKey)
                .map(Permission::fromValue)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " " +
                (user.getLastName() != null ? user.getLastName() : "")).trim();
        if (name.isEmpty()) {
            name = user.getEmail();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        AccountStatus effectiveStatus = user.getEffectiveStatus(now);
        boolean isExpiredTimeout = user.getStatus() != effectiveStatus;

        Artisan artisan = user.getArtisan();
        Client client = user.getClient();

        boolean isPremium = (artisan != null && artisan.isPremium())
                || (client != null && client.isPremium());
        boolean isTeacher = artisan != null && artisan.isTeacher();
        boolean isValidated = (artisan != null && artisan.isVerified())
                || (client != null && client.isVerified());

        return UserResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .name(name)
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .status(effectiveStatus)
                .emailVerified(user.isEmailVerified())
                .emailVerifiedAt(user.getEmailVerifiedAt())
                .permissions(roleNames)
                .isPremium(isPremium)
                .isValidated(isValidated)
                .isTeacher(isTeacher)
                .bannedUntil(isExpiredTimeout ? null : user.getBannedUntil())
                .banReason(isExpiredTimeout ? null : user.getBanReason())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
