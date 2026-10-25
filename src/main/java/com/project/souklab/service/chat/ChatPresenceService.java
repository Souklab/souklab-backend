package com.project.souklab.service.chat;

import org.springframework.messaging.Message;

import com.project.souklab.config.ChatProperties;
import com.project.souklab.dao.ConversationParticipantRepository;
import com.project.souklab.dto.chat.ChatEvent;
import com.project.souklab.dto.chat.ChatEventType;
import com.project.souklab.dto.chat.ChatMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class ChatPresenceService {
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatProperties properties;
    private final Clock clock;
    private final ConversationParticipantRepository participantRepository;
    private final Map<String, AtomicInteger> sessions = new ConcurrentHashMap<>();

    @Autowired
    public ChatPresenceService(SimpMessagingTemplate messagingTemplate,
                               ChatProperties properties,
                               Clock clock,
                               ConversationParticipantRepository participantRepository) {
        this.messagingTemplate = messagingTemplate;
        this.properties = properties;
        this.clock = clock;
        this.participantRepository = participantRepository;
    }

    public ChatPresenceService(SimpMessagingTemplate messagingTemplate, ChatProperties properties, Clock clock) {
        this(messagingTemplate, properties, clock, null);
    }

    @EventListener
    public void connected(SessionConnectedEvent event) {
        update(event.getMessage(), 1);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        update(event.getMessage(), -1);
    }

    private void update(Message<?> message, int delta) {
        if (message == null) {
            return;
        }
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getUser() == null) {
            return;
        }
        String username = accessor.getUser().getName();
        String stableHandle = "user-" + UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)).toString();
        AtomicInteger count = sessions.computeIfAbsent(username, ignored -> new AtomicInteger());
        int current = Math.max(0, count.addAndGet(delta));
        if (current == 0) {
            sessions.remove(username, count);
        }
        boolean online = current > 0;
        ChatEventType.Type eventType = online ? ChatEventType.Presence.ONLINE : ChatEventType.Presence.OFFLINE;

        List<String> partnerEmails = participantRepository != null
                ? participantRepository.findPartnerEmailsByUserEmail(username)
                : List.of();
        if (partnerEmails.isEmpty()) {
            return;
        }

        ChatEvent event = ChatEvent.create(
                properties.getWebsocketProtocolVersion(), eventType, null, null, null,
                LocalDateTime.now(clock), Map.of(ChatMetadata.Presence.USERNAME, stableHandle,
                        ChatMetadata.Presence.ONLINE, online));

        for (String partnerEmail : partnerEmails) {
            try {
                messagingTemplate.convertAndSendToUser(partnerEmail, properties.getPresenceDestination(), event);
            } catch (Exception e) {
                log.warn("Presence event delivery failed for partner: {}", partnerEmail, e);
            }
        }
    }
}
