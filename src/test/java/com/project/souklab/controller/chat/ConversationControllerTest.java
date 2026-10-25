package com.project.souklab.controller.chat;

import com.project.souklab.dto.chat.*;
import com.project.souklab.service.chat.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {
    @Mock ConversationService service;
    private ConversationController controller;

    @BeforeEach
    void setUp() { controller = new ConversationController(service); }

    @Test
    void createAndListDelegateToService() {
        ConversationResponse conversation = new ConversationResponse("c", "u", "User", false, null, null);
        when(service.createOrGet("u")).thenReturn(conversation);
        assertThat(controller.create(new CreateConversationRequest("u")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.list(false).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).createOrGet("u"); verify(service).list(false);
    }

    @Test
    void lifecycleEndpointsDelegate() {
        when(service.messages(eq("c"), isNull(), eq(20))).thenReturn(new MessagePageResponse(List.of(), null, true));
        when(service.send(eq("c"), any())).thenReturn(new MessageResponse("m", "c", "u", "x", false, null, null, List.of()));
        assertThat(controller.archive("c", new ArchiveConversationRequest(true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.messages("c", null, 20).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.send("c", new SendMessageRequest("k", "x", List.of())).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.edit("c", "m", new EditMessageRequest("y")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.delete("c", "m").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.read("c", new ReadReceiptRequest("m")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.uploadAttachment("c", new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes())).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).archive("c", true); verify(service).messages("c", null, 20); verify(service).delete("c", "m"); verify(service).markRead("c", "m");
    }

    @Test
    void optionalMessageSizeAndReadBodyUseServiceDefaults() {
        when(service.messages("c", null, 0)).thenReturn(new MessagePageResponse(List.of(), null, true));
        assertThat(controller.messages("c", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.read("c", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).messages("c", null, 0);
        verify(service).markRead("c", null);
    }
}
