package com.project.souklab.service.chat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.project.souklab.dto.chat.ConversationResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.dao.*;
import com.project.souklab.dto.chat.*;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.model.*;
import com.project.souklab.security.Permission;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.user.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {
    @Mock ConversationRepository conversationRepository;
    @Mock ConversationParticipantRepository participantRepository;
    @Mock MessageRepository messageRepository;
    @Mock MessageAttachmentUploadRepository uploadRepository;
    @Mock UserRepository userRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock NotificationService notificationService;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock StorageService storageService;
    @Mock FileValidator fileValidator;
    @InjectMocks ConversationService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private User sender;
    private User recipient;
    private Conversation conversation;
    private ChatProperties chatProperties;
    private AppStorageProperties storageProperties;

    @BeforeEach
    void setUp() {
        sender = user("sender", "sender@test", AccountStatus.ACTIVE, true);
        recipient = user("recipient", "recipient@test", AccountStatus.ACTIVE, true);
        conversation = new Conversation(); conversation.setId("conversation");
        ConversationParticipant first = participant(sender); ConversationParticipant second = participant(recipient);
        conversation.addParticipant(first); conversation.addParticipant(second);
        when(currentUserProvider.requireCurrentUser()).thenReturn(sender);
        storageProperties = new AppStorageProperties();
        storageProperties.setFileServingPrefix("/api/v1/files/");
        chatProperties = new ChatProperties();
        chatProperties.setMessageMaxLength(4000); chatProperties.setAttachmentMaxCount(5); chatProperties.setCursorLifetime(Duration.ofHours(24)); chatProperties.setTypingEventInterval(Duration.ofMillis(500)); chatProperties.setDefaultPageSize(50); chatProperties.setMinPageSize(1); chatProperties.setMaxPageSize(100); chatProperties.setWebsocketProtocolVersion("v1"); chatProperties.setEventDestination("/queue/chat/events"); chatProperties.setMessageDestinationPrefix("/queue/chat");
        service = new ConversationService(conversationRepository, participantRepository, messageRepository, uploadRepository, userRepository, currentUserProvider, notificationService, messagingTemplate, chatProperties, storageProperties, clock, storageService, fileValidator);
    }

    @Test
    void createOrGet_createsPrivateConversationAndUnarchivesRequester() {
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(conversationRepository.findBetween(sender, recipient)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(conversation.getParticipants().iterator().next()));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());

        ConversationResponse result = service.createOrGet(recipient.getId());

        assertThat(result.id()).isEqualTo("conversation");
        verify(participantRepository, times(2)).findByConversationAndUser(conversation, sender);
    }

    @Test
    void send_persistsMessageNotifiesRecipientAndIsIdempotent() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> { Message m = invocation.getArgument(0); m.setId("message"); m.setCreatedAt(LocalDateTime.now(clock)); return m; });
        MessageResponse result = service.send("conversation", new SendMessageRequest("key", "hello", List.of()));
        assertThat(result.content()).isEqualTo("hello");
        verify(notificationService).createForUser(recipient, "New message from " + sender.getPublicDisplayName(), NotificationType.Message.NEW, "conversation");
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    void send_existingIdempotencyKey_returnsOriginalWithoutDuplicateNotification() {
        Message existing = message("existing", sender, "old");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "key")).thenReturn(Optional.of(existing));
        assertThat(service.send("conversation", new SendMessageRequest("key", "retry", List.of())).id()).isEqualTo("existing");
        verify(messageRepository, never()).save(any()); verifyNoInteractions(notificationService);
    }

    @Test
    void edit_rejectsNonAuthor() {
        Message existing = message("message", recipient, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("message", conversation)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.edit("conversation", "message", new EditMessageRequest("changed"))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void send_rejectsUserWithoutMessagePermission() {
        sender.setPermissions(new HashSet<>());
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("key", "hello", List.of()))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_filtersByArchiveStateAndIncludesUnreadCount() {
        ConversationParticipant self = conversation.getParticipants().stream().filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        self.setArchived(false);
        when(conversationRepository.findAllForUser(sender)).thenReturn(List.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(2L);

        assertThat(service.list(false)).singleElement().satisfies(result -> {
            assertThat(result.unreadCount()).isEqualTo(2L);
            assertThat(result.archived()).isFalse();
        });
        assertThat(service.list(true)).isEmpty();
    }

    @Test
    void archive_updatesOnlyRequestersParticipant() {
        ConversationParticipant self = conversation.getParticipants().stream().filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));

        service.archive("conversation", true);

        assertThat(self.isArchived()).isTrue();
    }

    @Test
    void editAndDelete_allowAuthorAndSoftDeleteContent() {
        Message existing = message("message", sender, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("message", conversation)).thenReturn(Optional.of(existing));

        assertThat(service.edit("conversation", "message", new EditMessageRequest("changed")).content()).isEqualTo("changed");
        service.delete("conversation", "message");

        assertThat(existing.getDeletedAt()).isNotNull();
        assertThat(existing.getContent()).isEmpty();
    }

    @Test
    void markRead_isMonotonicAndTypingIsThrottled() {
        Message first = message("first", recipient, "one");
        Message second = message("second", recipient, "two");
        first.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        second.setCreatedAt(first.getCreatedAt().plusSeconds(1));
        ConversationParticipant self = conversation.getParticipants().stream().filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("first", conversation)).thenReturn(Optional.of(first));
        when(messageRepository.findById("second")).thenReturn(Optional.of(second));

        self.setLastReadMessageId("second");
        service.markRead("conversation", "first");
        assertThat(self.getLastReadMessageId()).isEqualTo("second");

        service.typing("conversation", true, "corr");
        service.typing("conversation", true, "corr");
        verify(messagingTemplate, times(1)).convertAndSendToUser(eq(recipient.getEmail()), eq("/queue/chat"), any());
    }

    @Test
    void uploadAttachment_validatesStoresAndReservesUpload() throws Exception {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        byte[] bytes = "content".getBytes();
        when(fileValidator.validateAndSanitize(any(), eq("note.pdf"), eq("application/pdf"), eq((long) bytes.length))).thenReturn(new ValidatedFile(new ByteArrayInputStream(bytes), "note.pdf", "application/pdf", bytes.length));
        when(storageService.store(any(), eq("note.pdf"), eq("application/pdf"), eq((long) bytes.length))).thenReturn(new StorageResult("key", "note.pdf", "application/pdf", bytes.length, clock.instant()));

        AttachmentUploadResponse result = service.uploadAttachment("conversation", new MockMultipartFile("file", "note.pdf", "application/pdf", bytes));

        assertThat(result.key()).isEqualTo("key");
        verify(uploadRepository).save(any(MessageAttachmentUpload.class));
    }

    @Test
    void messages_rejectsMalformedCursor() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        assertThatThrownBy(() -> service.messages("conversation", "not-a-cursor", 20)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void createOrGet_createsParticipantsWhenConversationDoesNotExist() {
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(conversationRepository.findBetween(sender, recipient)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(conversation.getParticipants().stream().filter(p -> p.getUser() == sender).findFirst().orElseThrow()));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());

        assertThat(service.createOrGet(recipient.getId()).id()).isEqualTo("conversation");
        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    void messages_supportsInitialAndStableCursorPages() {
        Message first = message("first", recipient, "hello");
        first.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(first), PageRequest.of(0, 1), 2));
        MessagePageResponse initial = service.messages("conversation", null, 1);
        assertThat(initial.nextCursor()).isNotBlank();

        when(messageRepository.findBefore(eq(conversation), eq(first.getCreatedAt()), eq("first"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        assertThat(service.messages("conversation", initial.nextCursor(), 1).content()).isEmpty();
        verify(messageRepository).findBefore(eq(conversation), eq(first.getCreatedAt()), eq("first"), any(PageRequest.class));
    }

    @Test
    void send_attachesOwnedUploadAndCleansStorageWhenPersistencePipelineFails() {
        MessageAttachmentUpload upload = new MessageAttachmentUpload();
        upload.setStorageKey("attachment-key"); upload.setOriginalFilename("note.pdf"); upload.setContentType("application/pdf"); upload.setSize(10);
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "key")).thenReturn(Optional.empty());
        when(uploadRepository.findByStorageKeyAndOwnerAndConversationAndUsedAtIsNull("attachment-key", sender, conversation)).thenReturn(Optional.of(upload));
        when(messageRepository.save(any(Message.class))).thenThrow(new IllegalStateException("database failure"));

        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("key", "hello", List.of("attachment-key"))))
                .isInstanceOf(IllegalStateException.class);
        verify(storageService).delete("attachment-key");
        assertThat(upload.getUsedAt()).isNotNull();
    }

    @Test
    void uploadAttachment_translatesReadFailureAndTypingStopPublishesStopEvent() throws Exception {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        MockMultipartFile file = mock(MockMultipartFile.class);
        when(file.getInputStream()).thenThrow(new IOException("read failure"));
        assertThatThrownBy(() -> service.uploadAttachment("conversation", file)).isInstanceOf(BadRequestException.class);

        service.typing("conversation", false, "stop");
        verify(messagingTemplate).convertAndSendToUser(eq(recipient.getEmail()), eq("/queue/chat"), any());
    }

    @Test
    void messageAndReadEventsAreDeliveredAfterTransactionCommit() {
        Message saved = message("committed", sender, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "commit-key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        ConversationParticipant self = conversation.getParticipants().stream().filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("committed", conversation)).thenReturn(Optional.of(saved));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.send("conversation", new SendMessageRequest("commit-key", "hello", List.of()));
            service.markRead("conversation", "committed");
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(anyString(), eq("/queue/chat/events"), any());
    }

    @Test
    void inactiveUnverifiedAndSuspendedUsersCannotSend() {
        sender.setStatus(AccountStatus.PENDING);
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("pending", "hello", List.of())))
                .isInstanceOf(ForbiddenException.class);
        sender.setStatus(AccountStatus.ACTIVE);
        sender.setEmailVerified(false);
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("unverified", "hello", List.of())))
                .isInstanceOf(ForbiddenException.class);
        sender.setEmailVerified(true);
        sender.setStatus(AccountStatus.SUSPENDED);
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("suspended", "hello", List.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sendRejectsConfiguredContentAndAttachmentLimits() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(eq(conversation), eq(sender), anyString())).thenReturn(Optional.empty());
        String oversized = "x".repeat(4001);
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("long", oversized, List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("many", "ok", List.of("1", "2", "3", "4", "5", "6"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createOrGetRejectsSelfAndIneligibleRecipient() {
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        assertThatThrownBy(() -> service.createOrGet(sender.getId())).isInstanceOf(BadRequestException.class);
        recipient.setStatus(AccountStatus.PENDING);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        assertThatThrownBy(() -> service.createOrGet(recipient.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void notFoundPathsRemainPrivateAndUseDomainExceptions() {
        when(userRepository.findById("missing-recipient")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createOrGet("missing-recipient"))
                .isInstanceOf(ResourceNotFoundException.class);

        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("missing-message", conversation))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.edit("conversation", "missing-message", new EditMessageRequest("updated")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete("conversation", "missing-message"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.markRead("conversation", "missing-message"))
                .isInstanceOf(ResourceNotFoundException.class);

        when(conversationRepository.findById("missing-conversation")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.archive("missing-conversation", true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void markReadWithNoMessagesDoesNothing() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        service.markRead("conversation", null);
        verify(participantRepository, never()).findByConversationAndUser(conversation, sender);
    }

    @Test
    void messagesClampsConfiguredPageSizeAtBothBounds() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());

        service.messages("conversation", null, 0);
        service.messages("conversation", null, 1000);

        verify(messageRepository, times(2)).findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any());
    }

    @Test
    void editRejectsOversizedContentAndDeleteRejectsNonAuthor() {
        Message otherMessage = message("other-message", recipient, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("other-message", conversation)).thenReturn(Optional.of(otherMessage));
        assertThatThrownBy(() -> service.delete("conversation", "other-message")).isInstanceOf(ForbiddenException.class);

        Message ownMessage = message("own-message", sender, "hello");
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("own-message", conversation)).thenReturn(Optional.of(ownMessage));
        assertThatThrownBy(() -> service.edit("conversation", "own-message", new EditMessageRequest("x".repeat(4001))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void expiredCursorIsRejectedAndMissingAttachmentIsInvalid() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        String expired = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2026-01-01T00:00|message|2025-12-31T23:59".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.messages("conversation", expired, 20))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.uploadAttachment("conversation", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectedUsersAndNonParticipantsAreDenied() {
        sender.setStatus(AccountStatus.REJECTED);
        assertThatThrownBy(() -> service.list(false)).isInstanceOf(ForbiddenException.class);
        sender.setStatus(AccountStatus.ACTIVE);
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(false);
        assertThatThrownBy(() -> service.archive("conversation", true)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void eventDeliveryFailureDoesNotFailMessageCommand() {
        Message saved = message("event-message", sender, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "event-key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        doThrow(new IllegalStateException("broker down")).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        assertThat(service.send("conversation", new SendMessageRequest("event-key", "hello", List.of())).content()).isEqualTo("hello");
    }

    @Test
    void nullAttachmentCollectionIsAcceptedAndPageCanBeEmptyWithNextCursor() {
        Message saved = message("null-attachments", sender, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "null-key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        assertThat(service.send("conversation", new SendMessageRequest("null-key", "hello", null)).id()).isEqualTo("null-attachments");

        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 2));
        assertThat(service.messages("conversation", null, 1).nextCursor()).isNull();
    }

    @Test
    void send_mapsPersistedAttachmentMetadataToDownloadUrl() {
        Message saved = message("with-attachment", sender, "photo");
        MessageAttachment attachment = new MessageAttachment();
        attachment.setId("attachment");
        attachment.setStorageKey("photo-key");
        attachment.setOriginalFilename("photo.jpg");
        attachment.setContentType("image/jpeg");
        attachment.setSize(42L);
        attachment.setMessage(saved);
        saved.addAttachment(attachment);
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "attachment-key"))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenReturn(saved);

        MessageResponse result = service.send("conversation", new SendMessageRequest("attachment-key", "photo", List.of()));

        assertThat(result.attachments()).singleElement().satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo("attachment");
            assertThat(mapped.filename()).isEqualTo("photo.jpg");
            assertThat(mapped.contentType()).isEqualTo("image/jpeg");
            assertThat(mapped.size()).isEqualTo(42L);
            assertThat(mapped.downloadUrl()).isEqualTo("/api/v1/files/photo-key");
        });
    }

    @Test
    void nullAttachmentCollectionIsCleanedWhenNotificationFails() {
        Message saved = message("notification-failure", sender, "hello");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "failure-key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        doThrow(new IllegalStateException("notification failure")).when(notificationService).createForUser(any(), anyString(), any(), anyString());
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("failure-key", "hello", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void list_mapsDeletedPreviewAndExistingReadMarker() {
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        self.setArchived(false);
        self.setLastReadMessageId("read-message");
        Message deleted = message("deleted-message", recipient, "secret");
        deleted.setDeletedAt(LocalDateTime.now(clock));
        Message read = message("read-message", recipient, "read");
        when(conversationRepository.findAllForUser(sender)).thenReturn(List.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.findById("read-message")).thenReturn(Optional.of(read));
        when(messageRepository.countUnread(conversation, sender, read.getCreatedAt())).thenReturn(0L);

        assertThat(service.list(false)).singleElement().satisfies(result -> {
            assertThat(result.lastMessagePreview()).isNull();
            assertThat(result.unreadCount()).isZero();
        });
    }

    @Test
    void send_rejectsUnknownAttachmentReservation() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "missing-key"))
                .thenReturn(Optional.empty());
        when(uploadRepository.findByStorageKeyAndOwnerAndConversationAndUsedAtIsNull("unknown", sender, conversation))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send("conversation",
                new SendMessageRequest("missing-key", "hello", List.of("unknown"))))
                .isInstanceOf(BadRequestException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void blankCursor_isEquivalentToInitialPage() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());

        service.messages("conversation", "   ", 20);

        verify(messageRepository).findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any());
        verify(messageRepository, never()).findBefore(any(), any(), any(), any());
    }

    @Test
    void markRead_doesNotMoveMarkerBackwardWhenPreviousTimestampIsNewer() {
        Message previous = message("previous", recipient, "newer");
        previous.setCreatedAt(LocalDateTime.now(clock).plusSeconds(2));
        Message target = message("target", recipient, "older");
        target.setCreatedAt(LocalDateTime.now(clock));
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        self.setLastReadMessageId(previous.getId());
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("target", conversation)).thenReturn(Optional.of(target));
        when(messageRepository.findById("previous")).thenReturn(Optional.of(previous));

        service.markRead("conversation", "target");

        assertThat(self.getLastReadMessageId()).isEqualTo("previous");
    }

    @Test
    void typing_afterThrottleIntervalPublishesAgain() {
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        chatProperties.setTypingEventInterval(Duration.ZERO);
        service.typing("conversation", true, "first");
        service.typing("conversation", true, "second");
        verify(messagingTemplate, times(2)).convertAndSendToUser(eq(recipient.getEmail()), eq("/queue/chat"), any());
    }

    @Test
    void markRead_handlesNullTimestampsWithoutMovingPastInvalidMarkers() {
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        Message previous = message("previous-null-time", recipient, "previous");
        previous.setCreatedAt(null);
        Message target = message("target-valid-time", recipient, "target");
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("target-valid-time", conversation)).thenReturn(Optional.of(target));
        when(messageRepository.findById("previous-null-time")).thenReturn(Optional.of(previous));

        service.markRead("conversation", target.getId());
        assertThat(self.getLastReadMessageId()).isEqualTo(target.getId());

        self.setLastReadMessageId(previous.getId());
        service.markRead("conversation", target.getId());
        assertThat(self.getLastReadMessageId()).isEqualTo(target.getId());

        Message targetWithoutTime = message("target-null-time", recipient, "target");
        targetWithoutTime.setCreatedAt(null);
        self.setLastReadMessageId(previous.getId());
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("target-null-time", conversation)).thenReturn(Optional.of(targetWithoutTime));
        service.markRead("conversation", targetWithoutTime.getId());
        assertThat(self.getLastReadMessageId()).isEqualTo(targetWithoutTime.getId());

        Message validPrevious = message("previous-valid-time", recipient, "previous");
        validPrevious.setCreatedAt(LocalDateTime.now(clock).plusSeconds(1));
        self.setLastReadMessageId(validPrevious.getId());
        when(messageRepository.findById("previous-valid-time")).thenReturn(Optional.of(validPrevious));
        service.markRead("conversation", targetWithoutTime.getId());
        assertThat(self.getLastReadMessageId()).isEqualTo(targetWithoutTime.getId());

        Message targetNewer = message("target-newer", recipient, "newer target");
        targetNewer.setCreatedAt(validPrevious.getCreatedAt().plusSeconds(1));
        when(messageRepository.findByIdAndConversationAndDeletedAtIsNull("target-newer", conversation)).thenReturn(Optional.of(targetNewer));
        service.markRead("conversation", targetNewer.getId());
        assertThat(self.getLastReadMessageId()).isEqualTo(targetNewer.getId());
    }

    @Test
    void send_requiresEnabledMessagePermissionWithCorrectKey() {
        AuthorizationPermission wrongPermission = new AuthorizationPermission();
        wrongPermission.setPermissionKey(Permission.Profile.READ.value());
        wrongPermission.setEnabled(true);
        sender.setPermissions(new HashSet<>(List.of(wrongPermission)));
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("wrong-key", "hello", List.of())))
                .isInstanceOf(ForbiddenException.class);

        AuthorizationPermission disabledPermission = new AuthorizationPermission();
        disabledPermission.setPermissionKey(Permission.Message.SEND.value());
        disabledPermission.setEnabled(false);
        sender.setPermissions(new HashSet<>(List.of(disabledPermission)));
        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("disabled-key", "hello", List.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_mapsActiveLastMessagePreview() {
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        Message latest = message("latest", recipient, "visible preview");
        when(conversationRepository.findAllForUser(sender)).thenReturn(List.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(new PageImpl<>(List.of(latest)));
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(1L);

        assertThat(service.list(false)).singleElement()
                .extracting(ConversationResponse::lastMessagePreview)
                .isEqualTo("visible preview");
    }

    @Test
    void list_handlesMissingLastReadMessageAndMalformedCursorPayload() {
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        self.setLastReadMessageId("missing-read");
        when(conversationRepository.findAllForUser(sender)).thenReturn(List.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.findById("missing-read")).thenReturn(Optional.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(0L);
        assertThat(service.list(false)).hasSize(1);

        String malformedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-01-01T00:00|only-two-fields".getBytes(StandardCharsets.UTF_8));
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        assertThatThrownBy(() -> service.messages("conversation", malformedPayload, 20))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createOrGet_whenNonPremiumClient_throwsForbiddenException() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(false).build();
        sender.setClient(client);

        assertThatThrownBy(() -> service.createOrGet(recipient.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("premium subscription is required");
    }

    @Test
    void send_whenNonPremiumClient_throwsForbiddenException() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(false).build();
        sender.setClient(client);

        assertThatThrownBy(() -> service.send("conversation", new SendMessageRequest("k", "hi", List.of())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("premium subscription is required");
    }

    @Test
    void uploadAttachment_whenNonPremiumClient_throwsForbiddenException() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(false).build();
        sender.setClient(client);

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "data".getBytes());
        assertThatThrownBy(() -> service.uploadAttachment("conversation", file))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("premium subscription is required");
    }

    @Test
    void createOrGet_whenPremiumClient_succeeds() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(true).build();
        sender.setClient(client);

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(conversationRepository.findBetween(sender, recipient)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(conversation.getParticipants().iterator().next()));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());

        ConversationResponse result = service.createOrGet(recipient.getId());

        assertThat(result.id()).isEqualTo("conversation");
    }

    @Test
    void send_whenPremiumClient_succeeds() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(true).build();
        sender.setClient(client);

        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(conversation, sender, "key")).thenReturn(Optional.empty());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message m = invocation.getArgument(0);
            m.setId("message");
            m.setCreatedAt(LocalDateTime.now(clock));
            return m;
        });

        MessageResponse result = service.send("conversation", new SendMessageRequest("key", "hello", List.of()));
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void toConversation_whenNonPremiumClientViewingArtisan_masksArtisanName() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(false).build();
        sender.setClient(client);

        recipient.setFirstName("Mohamed");
        recipient.setLastName("Artisan");
        Artisan artisan = Artisan.builder().id("art-abcde12345").user(recipient).build();
        recipient.setArtisan(artisan);

        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(0L);

        ConversationResponse response = service.get("conversation");
        assertThat(response.participantName()).isEqualTo("Artisan #12345");
    }

    @Test
    void toConversation_whenPremiumClientViewingArtisan_returnsRealName() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(true).build();
        sender.setClient(client);

        recipient.setFirstName("Mohamed");
        recipient.setLastName("Artisan");
        Artisan artisan = Artisan.builder().id("art-abcde12345").user(recipient).build();
        recipient.setArtisan(artisan);

        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(0L);

        ConversationResponse response = service.get("conversation");
        assertThat(response.participantName()).isEqualTo("Mohamed Artisan");
    }

    @Test
    void toConversation_whenAdminViewingArtisan_returnsRealName() {
        Client client = Client.builder().id(sender.getId()).user(sender).isPremium(false).build();
        sender.setClient(client);
        AuthorizationPermission adminPerm = new AuthorizationPermission();
        adminPerm.setPermissionKey(Permission.Admin.USERS.value());
        adminPerm.setEnabled(true);
        sender.addPermission(adminPerm);

        recipient.setFirstName("Mohamed");
        recipient.setLastName("Artisan");
        Artisan artisan = Artisan.builder().id("art-abcde12345").user(recipient).build();
        recipient.setArtisan(artisan);

        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(0L);

        ConversationResponse response = service.get("conversation");
        assertThat(response.participantName()).isEqualTo("Mohamed Artisan");
    }

    @Test
    void toConversation_includesSelfAndParticipantLastReadMessagePositions() {
        ConversationParticipant self = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == sender).findFirst().orElseThrow();
        ConversationParticipant partner = conversation.getParticipants().stream()
                .filter(p -> p.getUser() == recipient).findFirst().orElseThrow();
        self.setLastReadMessageId("msg-self-100");
        partner.setLastReadMessageId("msg-partner-200");

        when(conversationRepository.findById("conversation")).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByConversationAndUser(conversation, sender)).thenReturn(true);
        when(participantRepository.findByConversationAndUser(conversation, sender)).thenReturn(Optional.of(self));
        when(messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(eq(conversation), any())).thenReturn(Page.empty());
        when(messageRepository.findById("msg-self-100")).thenReturn(Optional.empty());
        when(messageRepository.countUnread(conversation, sender, null)).thenReturn(0L);

        ConversationResponse response = service.get("conversation");
        assertThat(response.lastReadMessageId()).isEqualTo("msg-self-100");
        assertThat(response.participantLastReadMessageId()).isEqualTo("msg-partner-200");
    }

    private User user(String id, String email, AccountStatus status, boolean verified) { User user = new User(); user.setId(id); user.setEmail(email); user.setStatus(status); user.setEmailVerified(verified); AuthorizationPermission permission = new AuthorizationPermission(); permission.setPermissionKey(Permission.Message.SEND.value()); permission.setEnabled(true); user.setPermissions(new HashSet<>(List.of(permission))); return user; }
    private ConversationParticipant participant(User user) { ConversationParticipant p = new ConversationParticipant(); p.setUser(user); return p; }
    private Message message(String id, User author, String content) { Message m = new Message(); m.setId(id); m.setConversation(conversation); m.setAuthor(author); m.setContent(content); m.setCreatedAt(LocalDateTime.now(clock)); return m; }
}
