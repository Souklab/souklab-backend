package com.project.souklab.controller.subscription;

import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.service.subscription.ChargilyWebhookService;
import com.project.souklab.service.subscription.InvalidWebhookSignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = ChargilyWebhookController.class)
class ChargilyWebhookControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChargilyWebhookService webhookService;

    @MockitoBean
    private ChargilyProperties chargilyProperties;

    @Test
    void webhookIsPublicAndProcessesRawBody() throws Exception {
        when(chargilyProperties.getRequestBodyLimit()).thenReturn(1024);

        mockMvc.perform(post("/api/v1/integrations/chargily/webhook")
                        .header("signature", "valid")
                        .contentType("application/json")
                        .content("{\"id\":\"evt-1\"}"))
                .andExpect(status().isOk());

        verify(webhookService).process(any(byte[].class), anyString());
    }

    @Test
    void invalidSignatureReturnsForbidden() throws Exception {
        when(chargilyProperties.getRequestBodyLimit()).thenReturn(1024);
        doThrow(new InvalidWebhookSignatureException()).when(webhookService).process(any(byte[].class), anyString());

        mockMvc.perform(post("/api/v1/integrations/chargily/webhook")
                        .header("signature", "invalid")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
