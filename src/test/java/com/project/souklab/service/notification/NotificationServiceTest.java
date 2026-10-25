package com.project.souklab.service.notification;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.config.NotificationProperties;

import com.project.souklab.dao.NotificationRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.notification.NotificationResponseDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.Notification;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying NotificationService creation, aggregation, administration broadcasts,
 * read-state transitions, soft deletion, transaction synchronization, and delivery resilience.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T10:15:30.00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("UTC");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private Clock fixedClock;
    private NotificationService notificationService;
    private User testUser;
    private LocalDateTime fixedNow;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_INSTANT, ZONE_ID);
        fixedNow = LocalDateTime.now(fixedClock);
        ChatProperties chatProperties = new ChatProperties();
        chatProperties.setNotificationDestination("/queue/notifications");
        NotificationProperties notificationProperties = new NotificationProperties();
        notificationProperties.setMaxMessageLength(4000);
        notificationService = new NotificationService(
                notificationRepository,
                userRepository,
                messagingTemplate,
                fixedClock,
                chatProperties,
                notificationProperties
        );
        SecurityContextHolder.clearContext();

        testUser = User.builder()
                .email("artisan@example.com")
                .firstName("Amine")
                .lastName("Benali")
                .build();
        testUser.setId("user-uuid-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * Helper to authenticate the test thread as the specified user email.
     *
     * @param email username or email for the authenticated principal
     */
    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "credentials", List.of())
        );
    }

    /**
     * Verifies that createForUser(user, message) delegates with null type and targetId,
     * saves the unread notification, and dispatches via WebSocket.
     */
    @Test
    @DisplayName("createForUser (2-arg): persists unread notification with null type/target and dispatches push")
    void createForUser_2arg_delegatesWithNullTypeAndTargetId_savesAndDispatches() {
        String message = "Welcome to SoukLab!";
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-uuid-10");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, message);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(testUser);
        assertThat(saved.getMessage()).isEqualTo(message);
        assertThat(saved.getType()).isNull();
        assertThat(saved.getTargetId()).isNull();
        assertThat(saved.isRead()).isFalse();

        assertThat(result.getId()).isEqualTo("notif-uuid-10");
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getType()).isNull();
        assertThat(result.getTargetId()).isNull();
        assertThat(result.isRead()).isFalse();
        assertThat(result.getCreatedAt()).isEqualTo(fixedNow);

        verify(messagingTemplate).convertAndSendToUser(
                eq("artisan@example.com"),
                eq("/queue/notifications"),
                eq(result)
        );
    }

    /**
     * Verifies that createForUser (4-arg) persists type and targetId on the notification entity.
     */
    @Test
    @DisplayName("createForUser (4-arg): persists notification with type and targetId and dispatches push")
    void createForUser_4arg_persistsTypeAndTargetId_savesAndDispatches() {
        String message = "New order received";
        NotificationType.Key type = NotificationType.Message.NEW;
        String targetId = "target-entity-99";

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-uuid-20");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, message, type, targetId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(testUser);
        assertThat(saved.getMessage()).isEqualTo(message);
        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.isRead()).isFalse();

        assertThat(result.getId()).isEqualTo("notif-uuid-20");
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getType()).isEqualTo(type);
        assertThat(result.getTargetId()).isEqualTo(targetId);

        verify(messagingTemplate).convertAndSendToUser(
                eq("artisan@example.com"),
                eq("/queue/notifications"),
                eq(result)
        );
    }

    /**
     * Verifies boundary validation: a message with exactly 4000 characters is accepted.
     */
    @Test
    @DisplayName("validateMessageLength: message with exactly 4000 characters passes validation")
    void validateMessageLength_whenExactly4000Chars_succeeds() {
        String valid4000 = "x".repeat(4000);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-4000");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, valid4000);

        assertThat(result.getMessage()).hasSize(4000);
        verify(notificationRepository).save(any(Notification.class));
    }

    /**
     * Verifies boundary validation: a message with 4001 characters throws BadRequestException.
     */
    @Test
    @DisplayName("validateMessageLength: message with 4001 characters throws BadRequestException")
    void validateMessageLength_whenExceeds4000Chars_throwsBadRequestException() {
        String invalid4001 = "x".repeat(4001);

        assertThatThrownBy(() -> notificationService.createForUser(testUser, invalid4001))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Notification message exceeds maximum allowed length of 4000 characters.");

        verify(notificationRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    /**
     * Verifies that a null message bypasses length validation and is persisted.
     */
    @Test
    @DisplayName("validateMessageLength: null message passes validation without exception")
    void validateMessageLength_whenMessageIsNull_passesValidation() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-null");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, null);

        assertThat(result.getMessage()).isNull();
        verify(notificationRepository).save(any(Notification.class));
    }

    /**
     * Verifies aggregated notification formatting when count is 1 and no previous notification exists.
     */
    @Test
    @DisplayName("createOrUpdateAggregatedNotification: formats 'username baseMessage' when count is 1")
    void createOrUpdateAggregatedNotification_whenCountIsOne_andNoExisting_createsNewNotificationWithSingleFormat() {
        NotificationType.Key type = NotificationType.Review.NEW;
        String targetId = "product-123";
        String baseMessage = "reviewed your product";
        String initiator = "Fatima";

        when(notificationRepository.findFirstByUserAndTypeAndTargetIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                testUser, type, targetId)).thenReturn(Optional.empty());

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-agg-1");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createOrUpdateAggregatedNotification(
                testUser, type, targetId, baseMessage, 1, initiator);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getMessage()).isEqualTo("Fatima reviewed your product");
        assertThat(saved.getUser()).isEqualTo(testUser);
        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.isRead()).isFalse();

        assertThat(result.getMessage()).isEqualTo("Fatima reviewed your product");
        verify(messagingTemplate).convertAndSendToUser(eq("artisan@example.com"), eq("/queue/notifications"), eq(result));
    }

    /**
     * Verifies aggregated notification formatting when count > 1 and no previous notification exists.
     */
    @Test
    @DisplayName("createOrUpdateAggregatedNotification: formats 'username and N others baseMessage' when count > 1")
    void createOrUpdateAggregatedNotification_whenCountGreaterThanOne_andNoExisting_createsNewNotificationWithAggregatedFormat() {
        NotificationType.Key type = NotificationType.Review.NEW;
        String targetId = "product-123";
        String baseMessage = "reviewed your product";
        String initiator = "Fatima";

        when(notificationRepository.findFirstByUserAndTypeAndTargetIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                testUser, type, targetId)).thenReturn(Optional.empty());

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-agg-2");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createOrUpdateAggregatedNotification(
                testUser, type, targetId, baseMessage, 4, initiator);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getMessage()).isEqualTo("Fatima and 3 others reviewed your product");
        assertThat(saved.isRead()).isFalse();

        assertThat(result.getMessage()).isEqualTo("Fatima and 3 others reviewed your product");
        verify(messagingTemplate).convertAndSendToUser(eq("artisan@example.com"), eq("/queue/notifications"), eq(result));
    }

    /**
     * Verifies that an existing aggregated notification updates its message, resets isRead to false,
     * updates createdAt to current clock instant, and is saved.
     */
    @Test
    @DisplayName("createOrUpdateAggregatedNotification: updates existing notification message, resets isRead, and refreshes createdAt")
    void createOrUpdateAggregatedNotification_whenExistingNotificationPresent_updatesMessageResetsReadAndUpdatesCreatedAt() {
        NotificationType.Key type = NotificationType.Review.NEW;
        String targetId = "product-123";
        LocalDateTime priorTime = fixedNow.minusHours(3);

        Notification existing = new Notification();
        existing.setId("existing-notif-uuid");
        existing.setUser(testUser);
        existing.setType(type);
        existing.setTargetId(targetId);
        existing.setMessage("Old review message");
        existing.setRead(true);
        existing.setCreatedAt(priorTime);

        when(notificationRepository.findFirstByUserAndTypeAndTargetIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                testUser, type, targetId)).thenReturn(Optional.of(existing));

        when(notificationRepository.save(existing)).thenReturn(existing);

        NotificationResponseDTO result = notificationService.createOrUpdateAggregatedNotification(
                testUser, type, targetId, "reviewed your product", 2, "Karim");

        assertThat(existing.getMessage()).isEqualTo("Karim and 1 others reviewed your product");
        assertThat(existing.isRead()).isFalse();
        assertThat(existing.getCreatedAt()).isEqualTo(fixedNow);

        assertThat(result.getId()).isEqualTo("existing-notif-uuid");
        assertThat(result.getMessage()).isEqualTo("Karim and 1 others reviewed your product");
        assertThat(result.isRead()).isFalse();
        assertThat(result.getCreatedAt()).isEqualTo(fixedNow);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo("existing-notif-uuid");

        verify(messagingTemplate).convertAndSendToUser(eq("artisan@example.com"), eq("/queue/notifications"), eq(result));
    }

    /**
     * Verifies that createOrUpdateAggregatedNotification throws BadRequestException when aggregated text exceeds 4000 characters.
     */
    @Test
    @DisplayName("createOrUpdateAggregatedNotification: throws BadRequestException when aggregated message exceeds 4000 chars")
    void createOrUpdateAggregatedNotification_whenAggregatedMessageExceeds4000_throwsBadRequestException() {
        String longBase = "x".repeat(3990);
        String initiator = "LongInitiatorName";

        assertThatThrownBy(() -> notificationService.createOrUpdateAggregatedNotification(
                testUser, NotificationType.Message.NEW, "tgt", longBase, 5, initiator))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Notification message exceeds maximum allowed length of 4000 characters.");

        verify(notificationRepository, never()).save(any());
    }

    /**
     * Verifies that notifyAdmins retrieves users with ROLE_ADMIN and invokes createForUser for each admin.
     */
    @Test
    @DisplayName("notifyAdmins: retrieves admins by role and creates notification for each admin user")
    void notifyAdmins_resolvesAdminUsersAndCallsCreateForUserForEach() {
        User admin1 = User.builder().email("admin1@souklab.com").build();
        admin1.setId("admin-uuid-1");
        User admin2 = User.builder().email("admin2@souklab.com").build();
        admin2.setId("admin-uuid-2");

        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(admin1, admin2));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-admin-" + n.getUser().getId());
            n.setCreatedAt(fixedNow);
            return n;
        });

        notificationService.notifyAdmins("System maintenance scheduled");

        verify(userRepository).findByPermissionKey(Permission.Admin.USERS.value());
        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(messagingTemplate).convertAndSendToUser(eq("admin1@souklab.com"), eq("/queue/notifications"), any(NotificationResponseDTO.class));
        verify(messagingTemplate).convertAndSendToUser(eq("admin2@souklab.com"), eq("/queue/notifications"), any(NotificationResponseDTO.class));
    }

    /**
     * Verifies that getCurrentUserNotifications maps page of Notification entities to PaginatedResponse of DTOs.
     */
    @Test
    @DisplayName("getCurrentUserNotifications: returns mapped PaginatedResponse for authenticated user")
    void getCurrentUserNotifications_returnsPaginatedResponseOfMappedDTOs() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));

        Notification notif1 = new Notification();
        notif1.setId("notif-1");
        notif1.setUser(testUser);
        notif1.setMessage("Message 1");
        notif1.setRead(false);
        notif1.setCreatedAt(fixedNow);

        Notification notif2 = new Notification();
        notif2.setId("notif-2");
        notif2.setUser(testUser);
        notif2.setMessage("Message 2");
        notif2.setRead(true);
        notif2.setCreatedAt(fixedNow.minusMinutes(5));

        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> page = new PageImpl<>(List.of(notif1, notif2), pageable, 2);
        when(notificationRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(testUser, pageable)).thenReturn(page);

        PaginatedResponse<NotificationResponseDTO> response = notificationService.getCurrentUserNotifications(pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getId()).isEqualTo("notif-1");
        assertThat(response.getContent().get(0).getMessage()).isEqualTo("Message 1");
        assertThat(response.getContent().get(0).isRead()).isFalse();
        assertThat(response.getContent().get(1).getId()).isEqualTo("notif-2");
        assertThat(response.getContent().get(1).getMessage()).isEqualTo("Message 2");
        assertThat(response.getContent().get(1).isRead()).isTrue();
        assertThat(response.getTotalElements()).isEqualTo(2);
    }

    /**
     * Verifies that getUnreadCount queries the repository for unread non-deleted notifications.
     */
    @Test
    @DisplayName("getUnreadCount: returns unread notification count for authenticated user")
    void getUnreadCount_returnsCountFromRepository() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));
        when(notificationRepository.countByUserAndIsReadFalseAndDeletedAtIsNull(testUser)).thenReturn(7L);

        long count = notificationService.getUnreadCount();

        assertThat(count).isEqualTo(7L);
        verify(notificationRepository).countByUserAndIsReadFalseAndDeletedAtIsNull(testUser);
    }

    /**
     * Verifies that markAsRead throws ResourceNotFoundException when the notification does not exist or is soft-deleted.
     */
    @Test
    @DisplayName("markAsRead: throws ResourceNotFoundException when notification is not found")
    void markAsRead_whenNotificationNotFound_throwsResourceNotFoundException() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));
        when(notificationRepository.findByIdAndUserAndDeletedAtIsNull("missing-id", testUser))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead("missing-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Notification not found with id: missing-id");

        verify(notificationRepository, never()).save(any());
    }

    /**
     * Verifies that markAsRead is a no-op on persistence when the notification is already marked as read.
     */
    @Test
    @DisplayName("markAsRead: returns DTO without saving when notification is already read")
    void markAsRead_whenAlreadyRead_returnsDtoWithoutSaving() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));

        Notification alreadyRead = new Notification();
        alreadyRead.setId("notif-read");
        alreadyRead.setUser(testUser);
        alreadyRead.setMessage("Already seen");
        alreadyRead.setRead(true);
        alreadyRead.setCreatedAt(fixedNow);

        when(notificationRepository.findByIdAndUserAndDeletedAtIsNull("notif-read", testUser))
                .thenReturn(Optional.of(alreadyRead));

        NotificationResponseDTO result = notificationService.markAsRead("notif-read");

        assertThat(result.getId()).isEqualTo("notif-read");
        assertThat(result.isRead()).isTrue();
        verify(notificationRepository, never()).save(any());
    }

    /**
     * Verifies that markAsRead updates isRead to true and saves when notification is currently unread.
     */
    @Test
    @DisplayName("markAsRead: updates isRead to true and persists when notification is currently unread")
    void markAsRead_whenUnread_marksReadAndSavesNotification() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));

        Notification unread = new Notification();
        unread.setId("notif-unread");
        unread.setUser(testUser);
        unread.setMessage("Unread message");
        unread.setRead(false);
        unread.setCreatedAt(fixedNow);

        when(notificationRepository.findByIdAndUserAndDeletedAtIsNull("notif-unread", testUser))
                .thenReturn(Optional.of(unread));

        NotificationResponseDTO result = notificationService.markAsRead("notif-unread");

        assertThat(unread.isRead()).isTrue();
        assertThat(result.isRead()).isTrue();
        verify(notificationRepository).save(unread);
    }

    /**
     * Verifies that markAllAsRead triggers the repository's bulk modifying query for the current user.
     */
    @Test
    @DisplayName("markAllAsRead: executes repository bulk update for authenticated user")
    void markAllAsRead_callsRepositoryBulkModifyingMethod() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));

        notificationService.markAllAsRead();

        verify(notificationRepository).markAllAsReadForUser(testUser);
    }

    /**
     * Verifies that deleteNotification sets the deletedAt timestamp using the clock and persists.
     */
    @Test
    @DisplayName("deleteNotification: sets deletedAt timestamp and persists soft deletion")
    void deleteNotification_whenFound_setsDeletedAtAndSaves() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));

        Notification notification = new Notification();
        notification.setId("notif-to-delete");
        notification.setUser(testUser);
        notification.setMessage("Message to delete");

        when(notificationRepository.findByIdAndUserAndDeletedAtIsNull("notif-to-delete", testUser))
                .thenReturn(Optional.of(notification));

        notificationService.deleteNotification("notif-to-delete");

        assertThat(notification.getDeletedAt()).isEqualTo(fixedNow);
        verify(notificationRepository).save(notification);
    }

    /**
     * Verifies that deleteNotification throws ResourceNotFoundException when notification is not found.
     */
    @Test
    @DisplayName("deleteNotification: throws ResourceNotFoundException when notification is not found")
    void deleteNotification_whenNotFound_throwsResourceNotFoundException() {
        authenticateAs("artisan@example.com");
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(testUser));
        when(notificationRepository.findByIdAndUserAndDeletedAtIsNull("missing-id", testUser))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.deleteNotification("missing-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Notification not found with id: missing-id");

        verify(notificationRepository, never()).save(any());
    }

    /**
     * Verifies that getCurrentUser throws UnauthorizedException when SecurityContext has no authentication.
     */
    @Test
    @DisplayName("getCurrentUser: throws UnauthorizedException when unauthenticated")
    void getCurrentUser_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> notificationService.getUnreadCount())
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated");

        verify(userRepository, never()).findByEmail(any());
    }

    /**
     * Verifies that getCurrentUser throws ResourceNotFoundException when authenticated username is not in database.
     */
    @Test
    @DisplayName("getCurrentUser: throws ResourceNotFoundException when authenticated user not found in repository")
    void getCurrentUser_whenUserNotFoundInDatabase_throwsResourceNotFoundException() {
        authenticateAs("ghost@example.com");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getUnreadCount())
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: ghost@example.com");
    }

    /**
     * Verifies that dispatchRealtimePush registers a transaction synchronization when a transaction is active,
     * deferring the WebSocket push until after commit.
     */
    @Test
    @DisplayName("dispatchRealtimePush: defers WebSocket dispatch until afterCommit when transaction is active")
    void dispatchRealtimePush_whenTransactionActive_defersPushUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-tx-1");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, "Transaction deferred message");

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);

        syncs.get(0).afterCommit();

        verify(messagingTemplate).convertAndSendToUser(
                eq("artisan@example.com"),
                eq("/queue/notifications"),
                eq(result)
        );
    }

    @Test
    @DisplayName("dispatchRealtimePush: post-commit broker failure is contained")
    void dispatchRealtimePush_whenPostCommitDeliveryFails_doesNotPropagate() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
                Notification n = invocation.getArgument(0);
                n.setId("notif-tx-failure");
                n.setCreatedAt(fixedNow);
                return n;
            });
            doThrow(new IllegalStateException("broker unavailable"))
                    .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

            notificationService.createForUser(testUser, "Deferred resilient message");

            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Verifies that dispatchRealtimePush sends the WebSocket payload immediately when no transaction is active.
     */
    @Test
    @DisplayName("dispatchRealtimePush: sends WebSocket payload immediately when no transaction is active")
    void dispatchRealtimePush_whenTransactionInactive_sendsImmediately() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-no-tx");
            n.setCreatedAt(fixedNow);
            return n;
        });

        NotificationResponseDTO result = notificationService.createForUser(testUser, "Immediate message");

        verify(messagingTemplate).convertAndSendToUser(
                eq("artisan@example.com"),
                eq("/queue/notifications"),
                eq(result)
        );
    }

    /**
     * Verifies that an exception during WebSocket push is caught and does not prevent the notification from being created.
     */
    @Test
    @DisplayName("dispatchRealtimePush: survives WebSocket delivery failure without propagating exception")
    void dispatchRealtimePush_whenMessagingTemplateThrows_catchesAndDoesNotPropagateException() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId("notif-resilient");
            n.setCreatedAt(fixedNow);
            return n;
        });

        doThrow(new RuntimeException("STOMP broker unavailable"))
                .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

        NotificationResponseDTO result = notificationService.createForUser(testUser, "Resilient message");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("notif-resilient");
        assertThat(result.getMessage()).isEqualTo("Resilient message");
        verify(notificationRepository).save(any(Notification.class));
    }
}
