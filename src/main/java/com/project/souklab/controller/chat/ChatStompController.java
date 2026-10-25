package com.project.souklab.controller.chat;

import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.security.access.AccessDeniedException;

import com.project.souklab.config.ChatProperties;
import com.project.souklab.dto.chat.*;
import com.project.souklab.service.chat.ConversationService;
import com.project.souklab.dto.chat.ChatEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.Payload;
import jakarta.validation.Valid;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;

@Controller
@RequiredArgsConstructor
public class ChatStompController {
    private final ConversationService conversationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatProperties properties;
    private final Clock clock;

    @MessageMapping("/v1/conversations/{conversationId}/messages.send")
    public void send(@DestinationVariable String conversationId, @Valid @Payload SendMessageRequest request, Principal principal) {
        MessageResponse response = withPrincipal(principal, () -> conversationService.send(conversationId, request));
        acknowledge(principal, ChatEvent.create(properties.getWebsocketProtocolVersion(), ChatEventType.Command.ACKNOWLEDGED, conversationId, response.id(), request.idempotencyKey(), LocalDateTime.now(clock), response));
    }

    @MessageMapping("/v1/conversations/{conversationId}/messages.edit")
    public void edit(@DestinationVariable String conversationId, EditMessageCommand command, Principal principal) {
        MessageResponse response = withPrincipal(principal, () -> conversationService.edit(conversationId, command.messageId(), new EditMessageRequest(command.content())));
        acknowledge(principal, ChatEvent.create(properties.getWebsocketProtocolVersion(), ChatEventType.Command.ACKNOWLEDGED, conversationId, response.id(), command.correlationId(), LocalDateTime.now(clock), response));
    }

    @MessageMapping("/v1/conversations/{conversationId}/messages.delete")
    public void delete(@DestinationVariable String conversationId, MessageCommand command, Principal principal) {
        withPrincipal(principal, () -> { conversationService.delete(conversationId, command.messageId()); return null; });
        acknowledge(principal, ChatEvent.create(properties.getWebsocketProtocolVersion(), ChatEventType.Command.ACKNOWLEDGED, conversationId, command.messageId(), command.correlationId(), LocalDateTime.now(clock), null));
    }

    @MessageMapping("/v1/conversations/{conversationId}/read")
    public void read(@DestinationVariable String conversationId, MessageCommand command, Principal principal) {
        withPrincipal(principal, () -> { conversationService.markRead(conversationId, command.messageId()); return null; });
        acknowledge(principal, ChatEvent.create(properties.getWebsocketProtocolVersion(), ChatEventType.Read.UpTo.EVENT, conversationId, command.messageId(), command.correlationId(), LocalDateTime.now(clock), null));
    }

    @MessageMapping("/v1/conversations/{conversationId}/typing.start")
    public void typingStart(@DestinationVariable String conversationId, TypingCommand command, Principal principal) {
        withPrincipal(principal, () -> { conversationService.typing(conversationId, true, command.correlationId()); return null; });
    }

    @MessageMapping("/v1/conversations/{conversationId}/typing.stop")
    public void typingStop(@DestinationVariable String conversationId, TypingCommand command, Principal principal) {
        withPrincipal(principal, () -> { conversationService.typing(conversationId, false, command.correlationId()); return null; });
    }

    private void acknowledge(Principal principal, ChatEvent event) { messagingTemplate.convertAndSendToUser(principal.getName(), properties.getMessageDestinationPrefix(), event); }

    @MessageExceptionHandler
    public void handleError(Throwable error, Principal principal) {
        if (principal != null) {
            ChatEvent event = ChatEvent.create(properties.getWebsocketProtocolVersion(), ChatEventType.Command.ERROR, null, null, null, LocalDateTime.now(clock), Map.of(ChatMetadata.Command.MESSAGE, error.getMessage() == null ? "Chat command failed" : error.getMessage()));
            acknowledge(principal, event);
        }
    }
    private <T> T withPrincipal(Principal principal, Callable<T> action) {
        if (!(principal instanceof UsernamePasswordAuthenticationToken authentication)) throw new AccessDeniedException("Authenticated STOMP principal required");
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
        try { return action.call(); } catch (AccessDeniedException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); } finally { SecurityContextHolder.clearContext(); }
    }

    public record MessageCommand(String messageId, String correlationId) {}
    public record EditMessageCommand(String messageId, String correlationId, String content) {}
}
