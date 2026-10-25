package com.project.souklab.controller.subscription;

import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.service.subscription.ChargilyWebhookService;
import com.project.souklab.service.subscription.InvalidWebhookSignatureException;
import com.project.souklab.service.subscription.MalformedWebhookException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/chargily")
@RequiredArgsConstructor
public class ChargilyWebhookController {
    private static final String SIGNATURE_HEADER = "signature";
    private final ChargilyWebhookService webhookService;
    private final ChargilyProperties chargilyProperties;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (signature == null || signature.isBlank()
                || rawBody == null || rawBody.length > chargilyProperties.getRequestBodyLimit()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            webhookService.process(rawBody, signature);
            return ResponseEntity.ok().build();
        } catch (InvalidWebhookSignatureException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (MalformedWebhookException exception) {
            return ResponseEntity.badRequest().build();
        }
    }
}
