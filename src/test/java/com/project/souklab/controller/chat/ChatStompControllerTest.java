package com.project.souklab.controller.chat;
import org.assertj.core.api.Assertions;
import org.springframework.security.access.AccessDeniedException;

import com.project.souklab.dto.chat.TypingCommand;

import com.project.souklab.config.ChatProperties;
import com.project.souklab.dto.chat.MessageResponse;
import com.project.souklab.dto.chat.SendMessageRequest;
import com.project.souklab.service.chat.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatStompControllerTest {
    @Mock ConversationService service;
    @Mock SimpMessagingTemplate messagingTemplate;
    private ChatStompController controller;
    private Principal principal;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties();
        properties.setWebsocketProtocolVersion("v1");
        properties.setMessageDestinationPrefix("/queue/chat");
        controller = new ChatStompController(service, messagingTemplate, properties, Clock.systemUTC());
        principal = new UsernamePasswordAuthenticationToken("user@test", null, List.of());
    }

    @Test
    void send_acknowledgesSuccessfulCommand() {
        MessageResponse response = new MessageResponse("m1", "c1", "u1", "hello", false, null, null, List.of());
        when(service.send(eq("c1"), any(SendMessageRequest.class))).thenReturn(response);
        controller.send("c1", new SendMessageRequest("key", "hello", List.of()), principal);
        verify(messagingTemplate).convertAndSendToUser(eq("user@test"), eq("/queue/chat"), any());
    }

    @Test
    void error_withoutPrincipal_doesNotPublish() {
        controller.handleError(new IllegalStateException("failure"), null);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void edit_delete_read_and_typing_commandsDelegateAndAcknowledge() {
        MessageResponse response = new MessageResponse("m1", "c1", "u1", "changed", false, null, null, List.of());
        when(service.edit(eq("c1"), eq("m1"), any())).thenReturn(response);

        controller.edit("c1", new ChatStompController.EditMessageCommand("m1", "edit-correlation", "changed"), principal);
        controller.delete("c1", new ChatStompController.MessageCommand("m1", "delete-correlation"), principal);
        controller.read("c1", new ChatStompController.MessageCommand("m1", "read-correlation"), principal);
        controller.typingStart("c1", new TypingCommand("typing-correlation"), principal);
        controller.typingStop("c1", new TypingCommand("stop-correlation"), principal);

        verify(service).delete("c1", "m1");
        verify(service).markRead("c1", "m1");
        verify(service).typing("c1", true, "typing-correlation");
        verify(service).typing("c1", false, "stop-correlation");
        verify(messagingTemplate, times(3)).convertAndSendToUser(eq("user@test"), eq("/queue/chat"), any());
    }

    @Test
    void authenticatedErrorPublishesCommandError() {
        controller.handleError(new IllegalStateException("failure"), principal);
        verify(messagingTemplate).convertAndSendToUser(eq("user@test"), eq("/queue/chat"), any());
    }

    @Test
    void errorWithoutMessageUsesSafeFallback() {
        controller.handleError(new IllegalStateException(), principal);
        verify(messagingTemplate).convertAndSendToUser(eq("user@test"), eq("/queue/chat"), any());
    }

    @Test
    void commandWithoutAuthenticationIsRejected() {
        Assertions.assertThatThrownBy(() -> controller.send("c1", new SendMessageRequest("key", "hello", List.of()), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void commandPreservesAccessDeniedAndWrapsOtherFailures() {
        doThrow(new AccessDeniedException("denied"))
                .when(service).send(eq("c1"), any(SendMessageRequest.class));
        Assertions.assertThatThrownBy(() -> controller.send(
                        "c1", new SendMessageRequest("denied-key", "hello", List.of()), principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("denied");

        reset(service);
        doThrow(new IllegalStateException("service failure"))
                .when(service).send(eq("c1"), any(SendMessageRequest.class));
        Assertions.assertThatThrownBy(() -> controller.send(
                        "c1", new SendMessageRequest("failure-key", "hello", List.of()), principal))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
