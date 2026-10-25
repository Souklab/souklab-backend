package com.project.souklab.service.chat;

import java.io.IOException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.dao.ConversationParticipantRepository;
import com.project.souklab.dao.ConversationRepository;
import com.project.souklab.dao.MessageRepository;
import com.project.souklab.dao.MessageAttachmentUploadRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.chat.*;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.*;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.validation.FileValidator;
import org.springframework.web.multipart.MultipartFile;
import com.project.souklab.security.Permission;
import com.project.souklab.security.ViewerPremiumResolver;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.service.notification.AfterCommitAction;
import com.project.souklab.service.user.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentUploadRepository attachmentUploadRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatProperties chatProperties;
    private final AppStorageProperties appStorageProperties;
    private final Clock clock;
    private final StorageService storageService;
    private final FileValidator fileValidator;
    private final Map<String, Instant> lastTypingEvent = new ConcurrentHashMap<>();
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    @Transactional
    public ConversationResponse createOrGet(String recipientId) {
        User current = requireCurrentUser(true);
        User recipient = userRepository.findById(recipientId).orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));
        if (current.getId().equals(recipient.getId())) throw new BadRequestException("You cannot message yourself");
        requireEligible(recipient, false);
        Conversation conversation = conversationRepository.findBetween(current, recipient).orElseGet(() -> {
            Conversation created = new Conversation();
            ConversationParticipant first = new ConversationParticipant(); first.setUser(current); first.setArchived(false);
            ConversationParticipant second = new ConversationParticipant(); second.setUser(recipient); second.setArchived(false);
            created.addParticipant(first); created.addParticipant(second);
            return conversationRepository.save(created);
        });
        participantRepository.findByConversationAndUser(conversation, current).ifPresent(p -> p.setArchived(false));
        return toConversation(conversation, current);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(boolean archived) {
        User current = requireCurrentUser(false);
        return conversationRepository.findAllForUser(current).stream()
                .map(c -> participantRepository.findByConversationAndUser(c, current)
                        .filter(p -> p.isArchived() == archived)
                        .map(p -> toConversation(c, current, p))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(String id) {
        User current = requireCurrentUser(false);
        return toConversation(requireParticipant(id, current), current);
    }

    @Transactional
    public void archive(String id, boolean archived) {
        User current = requireCurrentUser(false);
        Conversation c = requireParticipant(id, current);
        participantRepository.findByConversationAndUser(c, current).orElseThrow().setArchived(archived);
    }

    @Transactional(readOnly = true)
    public MessagePageResponse messages(String id, String cursor, int size) {
        User current = requireCurrentUser(false);
        Conversation c = requireParticipant(id, current);
        int requestedSize = size <= 0 ? chatProperties.getDefaultPageSize() : size;
        int safeSize = Math.min(Math.max(requestedSize, chatProperties.getMinPageSize()), chatProperties.getMaxPageSize());
        Page<Message> page;
        MessageCursor decoded = decodeCursor(cursor);
        PageRequest request = PageRequest.of(0, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        page = decoded == null ? messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(c, request) : messageRepository.findBefore(c, decoded.time(), decoded.id(), request);
        String next = page.hasNext() && !page.isEmpty() ? encodeCursor(page.getContent().get(page.getContent().size() - 1)) : null;
        return new MessagePageResponse(page.getContent().stream().map(this::toMessage).toList(), next, !page.hasNext());
    }

    @Transactional
    public MessageResponse send(String id, SendMessageRequest request) {
        User current = requireCurrentUser(true);
        Conversation c = requireParticipant(id, current);
        Message existing = messageRepository.findByConversationAndAuthorAndIdempotencyKeyAndDeletedAtIsNull(c, current, request.idempotencyKey()).orElse(null);
        if (existing != null) return toMessage(existing);
        if (request.attachmentKeys() != null && request.attachmentKeys().size() > chatProperties.getAttachmentMaxCount()) throw new BadRequestException("Too many attachments");
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank() && (request.attachmentKeys() == null || request.attachmentKeys().isEmpty())) throw new BadRequestException("Message content or at least one attachment is required");
        if (content.length() > chatProperties.getMessageMaxLength()) throw new BadRequestException("Message content exceeds configured limit");
        Message message = new Message();
        message.setConversation(c);
        message.setAuthor(current);
        message.setContent(content);
        message.setIdempotencyKey(request.idempotencyKey());
        // CreationTimestamp is assigned during flush; set it explicitly so the 201 response
        // and realtime event contain the same timestamp as the persisted message.
        message.setCreatedAt(LocalDateTime.now(clock));
        if (request.attachmentKeys() != null) {
            for (String key : request.attachmentKeys()) {
                MessageAttachmentUpload upload = attachmentUploadRepository.findByStorageKeyAndOwnerAndConversationAndUsedAtIsNull(key, current, c).orElseThrow(() -> new BadRequestException("Attachment is not owned by the sender for this conversation"));
                MessageAttachment attachment = new MessageAttachment(); attachment.setMessage(message); attachment.setStorageKey(upload.getStorageKey()); attachment.setOriginalFilename(upload.getOriginalFilename()); attachment.setContentType(upload.getContentType()); attachment.setSize(upload.getSize()); message.addAttachment(attachment); upload.setUsedAt(LocalDateTime.now(clock));
            }
        }
        try {
            Message saved = messageRepository.save(message);
            if (activityEventService != null) {
                activityEventService.record(AnalyticsEvent.Message.SENT, current.getId(), saved.getId(),
                        Map.of(AnalyticsMetadata.Message.Conversation.ID, c.getId()));
            }
            c.setUpdatedAt(LocalDateTime.now(clock));
            User recipient = otherParticipant(c, current).getUser();
            notificationService.createForUser(recipient, "New message from " + current.getPublicDisplayName(), NotificationType.Message.NEW, c.getId());
            dispatch(c, ChatEventType.Message.CREATED, saved, null);
            return toMessage(saved);
        } catch (RuntimeException exception) {
            if (request.attachmentKeys() != null) request.attachmentKeys().forEach(storageService::delete);
            throw exception;
        }
    }

    @Transactional
    public MessageResponse edit(String conversationId, String messageId, EditMessageRequest request) {
        User current = requireCurrentUser(true); Conversation c = requireParticipant(conversationId, current);
        Message message = messageRepository.findByIdAndConversationAndDeletedAtIsNull(messageId, c).orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (!message.getAuthor().getId().equals(current.getId())) throw new ForbiddenException("Only the author may edit this message");
        if (request.content().length() > chatProperties.getMessageMaxLength()) throw new BadRequestException("Message content exceeds configured limit");
        message.setContent(request.content()); message.setEditedAt(LocalDateTime.now(clock));
        dispatch(c, ChatEventType.Message.UPDATED, message, null); return toMessage(message);
    }

    @Transactional
    public void delete(String conversationId, String messageId) {
        User current = requireCurrentUser(true); Conversation c = requireParticipant(conversationId, current);
        Message message = messageRepository.findByIdAndConversationAndDeletedAtIsNull(messageId, c).orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (!message.getAuthor().getId().equals(current.getId())) throw new ForbiddenException("Only the author may delete this message");
                message.setDeletedAt(LocalDateTime.now(clock)); message.setContent(""); dispatch(c, ChatEventType.Message.DELETED, message, null);
    }

    @Transactional
    public void markRead(String conversationId, String messageId) {
        User current = requireCurrentUser(false); Conversation c = requireParticipant(conversationId, current);
        Message target = messageId == null ? messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(c, PageRequest.of(0, 1)).stream().findFirst().orElse(null) : messageRepository.findByIdAndConversationAndDeletedAtIsNull(messageId, c).orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (target != null) {
            ConversationParticipant participant = participantRepository.findByConversationAndUser(c, current).orElseThrow();
            Message previous = participant.getLastReadMessageId() == null ? null : messageRepository.findById(participant.getLastReadMessageId()).orElse(null);
            if (shouldAdvanceReadMarker(previous, target)) participant.setLastReadMessageId(target.getId());
            dispatchRead(c, target, current);
        }
    }

    @Transactional(readOnly = true)
    public void typing(String conversationId, boolean typing, String correlationId) {
        User current = requireCurrentUser(true);
        Conversation conversation = requireParticipant(conversationId, current);
        Instant now = clock.instant();
        if (typing) {
            Instant previous = lastTypingEvent.putIfAbsent(current.getId() + ":" + conversationId, now);
            if (previous != null && now.isBefore(previous.plus(chatProperties.getTypingEventInterval()))) return;
            lastTypingEvent.put(current.getId() + ":" + conversationId, now);
        } else {
            lastTypingEvent.remove(current.getId() + ":" + conversationId);
        }
        User recipient = otherParticipant(conversation, current).getUser();
        ChatEventType.Type eventType = typing ? ChatEventType.Typing.STARTED : ChatEventType.Typing.STOPPED;
        ChatEvent event = ChatEvent.create(chatProperties.getWebsocketProtocolVersion(), eventType,
                conversationId, null, correlationId, LocalDateTime.now(clock),
                Map.of(ChatMetadata.Typing.USERNAME, current.getId(), ChatMetadata.Typing.TYPING, typing));
        messagingTemplate.convertAndSendToUser(recipient.getEmail(), chatProperties.getMessageDestinationPrefix(), event);
    }

    @Transactional
    public AttachmentUploadResponse uploadAttachment(String conversationId, MultipartFile file) {
        User current = requireCurrentUser(true); requireParticipant(conversationId, current);
        if (file == null || file.isEmpty()) throw new BadRequestException("Attachment is required");
        try {
            var validated = fileValidator.validateAndSanitize(file.getInputStream(), file.getOriginalFilename(), file.getContentType(), file.getSize());
            StorageResult stored = storageService.store(validated.content(), validated.sanitizedFilename(), validated.detectedMimeType(), validated.size());
            MessageAttachmentUpload upload = new MessageAttachmentUpload(); upload.setOwner(current); upload.setConversation(requireParticipant(conversationId, current)); upload.setStorageKey(stored.key()); upload.setOriginalFilename(stored.originalFilename()); upload.setContentType(stored.contentType()); upload.setSize(stored.size()); attachmentUploadRepository.save(upload);
            return new AttachmentUploadResponse(stored.key(), stored.originalFilename(), stored.contentType(), stored.size());
        } catch (IOException e) { throw new BadRequestException("Unable to read attachment"); }
    }

    private User requireCurrentUser(boolean sending) { User user = currentUserProvider.requireCurrentUser(); requireEligible(user, sending); return user; }
    private void requireEligible(User user, boolean sending) {
        if (user.getStatus() != AccountStatus.ACTIVE || !user.isEmailVerified()) {
            throw new ForbiddenException("Account is not eligible for messaging");
        }
        if (sending) {
            if (isClient(user)) {
                boolean isPremium = user.getClient() != null && user.getClient().isPremium();
                if (!isPremium) {
                    throw new ForbiddenException("A premium subscription is required for clients to initiate or send messages");
                }
            }
            if (user.getPermissions().stream().noneMatch(p -> p.isEnabled() && Permission.Message.SEND.matches(p.getPermissionKey()))) {
                throw new ForbiddenException("Account is not eligible to send messages");
            }
        }
    }
    private Conversation requireParticipant(String id, User user) { Conversation c = conversationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Conversation not found")); if (!participantRepository.existsByConversationAndUser(c, user)) throw new ResourceNotFoundException("Conversation not found"); return c; }
    private ConversationParticipant otherParticipant(Conversation c, User current) { return c.getParticipants().stream().filter(p -> !p.getUser().getId().equals(current.getId())).findFirst().orElseThrow(); }
    private boolean shouldAdvanceReadMarker(Message previous, Message target) {
        if (previous == null) return true;
        if (previous.getCreatedAt() == null) return true;
        if (target.getCreatedAt() == null) return true;
        return target.getCreatedAt().isAfter(previous.getCreatedAt());
    }
    private ConversationResponse toConversation(Conversation c, User current) {
        ConversationParticipant self = participantRepository.findByConversationAndUser(c, current).orElseThrow();
        return toConversation(c, current, self);
    }

    private ConversationResponse toConversation(Conversation c, User current, ConversationParticipant self) {
        ConversationParticipant p = otherParticipant(c, current);
        String preview = messageRepository.findByConversationAndDeletedAtIsNullOrderByCreatedAtDesc(c, PageRequest.of(0, 1))
                .stream().findFirst().map(Message::getContent).orElse(null);
        LocalDateTime after = self.getLastReadMessageId() == null ? null
                : messageRepository.findById(self.getLastReadMessageId()).map(Message::getCreatedAt).orElse(null);
        long unread = messageRepository.countUnread(c, current, after);
        String participantName = resolveParticipantName(p.getUser(), current);
        String role = p.getUser().getArtisan() != null ? "ARTISAN" : p.getUser().getClient() != null ? "CLIENT" : "USER";
        String participantAvatarUrl = isParticipantIdentityMasked(p.getUser(), current)
                ? null : p.getUser().getAvatarUrl();
        return new ConversationResponse(c.getId(), p.getUser().getId(), participantName, self.isArchived(), preview, unread,
                c.getUpdatedAt(), participantAvatarUrl, role, self.getLastReadMessageId(), p.getLastReadMessageId());
    }

    private boolean isParticipantIdentityMasked(User participant, User viewer) {
        if (participant == null || participant.getArtisan() == null) return false;
        boolean isAdmin = viewer != null && viewer.getPermissions() != null && viewer.getPermissions().stream()
                .anyMatch(p -> p.isEnabled() && Permission.Admin.USERS.matches(p.getPermissionKey()));
        boolean isSelf = viewer != null && Objects.equals(viewer.getId(), participant.getId());
        return !isAdmin && !isSelf && isClient(viewer)
                && (viewer.getClient() == null || !viewer.getClient().isPremium());
    }

    private String resolveParticipantName(User participant, User viewer) {
        if (participant == null) return "Unknown";
        if (participant.getArtisan() != null && isParticipantIdentityMasked(participant, viewer)) {
            return ViewerPremiumResolver.maskArtisanName(participant.getArtisan().getId());
        }
        return participant.getName();
    }

    private boolean isClient(User user) {
        if (user == null) return false;
        if (user.getArtisan() != null) return false;
        if (user.getClient() != null) return true;
        if (user.getPermissions() != null) {
            return user.getPermissions().stream()
                    .anyMatch(p -> p.isEnabled() && Permission.Client.FAVORITES.matches(p.getPermissionKey()));
        }
        return false;
    }

    private MessageResponse toMessage(Message m) {
        return new MessageResponse(
                m.getId(),
                m.getConversation().getId(),
                m.getAuthor().getId(),
                m.getDeletedAt() == null ? m.getContent() : "",
                m.getDeletedAt() != null,
                m.getCreatedAt(),
                m.getEditedAt(),
                m.getAttachments().stream().map(a -> new MessageAttachmentResponse(
                        a.getId(),
                        a.getOriginalFilename(),
                        a.getContentType(),
                        a.getSize(),
                        appStorageProperties.toUrl(a.getStorageKey()))).toList());
    }

    private String encodeCursor(Message message) {
        LocalDateTime expiry = LocalDateTime.now(clock).plus(chatProperties.getCursorLifetime());
        String value = message.getCreatedAt() + "|" + message.getId() + "|" + expiry;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private MessageCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 3);
            LocalDateTime expiry = LocalDateTime.parse(values[2]);
            if (LocalDateTime.now(clock).isAfter(expiry)) throw new BadRequestException("Message cursor has expired");
            return new MessageCursor(LocalDateTime.parse(values[0]), values[1]);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Invalid message cursor");
        }
    }

    private void dispatch(Conversation c, ChatEventType.Type type, Message m, String correlationId) {
        ChatEvent event = ChatEvent.create(chatProperties.getWebsocketProtocolVersion(), type,
                c.getId(), m.getId(), correlationId, LocalDateTime.now(clock), toMessage(m));
        dispatchAfterCommit(c, event);
    }

    private void dispatchRead(Conversation c, Message message, User reader) {
        ChatEvent event = ChatEvent.create(chatProperties.getWebsocketProtocolVersion(), ChatEventType.Read.UpTo.EVENT,
                c.getId(), message.getId(), null, LocalDateTime.now(clock),
                Map.of(ChatMetadata.Read.READER, reader.getId(), ChatMetadata.Read.MESSAGE_ID, message.getId()));
        dispatchAfterCommit(c, event);
    }

    private void dispatchAfterCommit(Conversation c, ChatEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new AfterCommitAction(() -> send(c, event)));
        } else {
            send(c, event);
        }
    }

    private void send(Conversation c, ChatEvent event) {
        for (ConversationParticipant p : c.getParticipants()) {
            try {
                messagingTemplate.convertAndSendToUser(p.getUser().getEmail(), chatProperties.getEventDestination(), event);
            } catch (Exception e) {
                log.warn("Chat event delivery failed", e);
            }
        }
    }
}
