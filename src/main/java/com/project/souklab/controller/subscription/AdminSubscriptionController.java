package com.project.souklab.controller.subscription;

import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.subscription.FinancialReasonRequest;
import com.project.souklab.dto.subscription.ManualSubscriptionGrantRequest;
import com.project.souklab.dto.subscription.SubscriptionResponse;
import com.project.souklab.dto.subscription.PaymentResponse;
import com.project.souklab.dto.subscription.AdminWebhookLogResponse;
import com.project.souklab.dto.subscription.PaymentStateCorrectionRequest;
import com.project.souklab.dto.subscription.SubscriptionStateCorrectionRequest;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.PaymentWebhookLogRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;
import com.project.souklab.service.subscription.AdminSubscriptionService;
import com.project.souklab.service.subscription.ChargilyWebhookEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("@accessControl.canManageFinancialOperations(authentication)")
public class AdminSubscriptionController {
    private final AdminSubscriptionService subscriptionService;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookLogRepository webhookLogRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubscriptionResponse>>> subscriptions(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.all(Math.min(Math.max(limit, 1), 200), query)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscriptionById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.getSubscriptionById(id)));
    }

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> payments(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.getPayments(limit, query)));
    }

    @GetMapping("/webhooks")
    public ResponseEntity<ApiResponse<List<AdminWebhookLogResponse>>> webhooks(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String query) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AdminWebhookLogResponse> result = (query == null || query.isBlank()
                ? webhookLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                : webhookLogRepository.findByProviderEventIdContainingIgnoreCaseOrEventTypeContainingIgnoreCaseOrProviderCheckoutIdContainingIgnoreCase(query, query, query, pageable)).stream().map(value -> AdminWebhookLogResponse.builder()
                .id(value.getId()).providerEventId(value.getProviderEventId())
                .eventType(ChargilyWebhookEvent.Checkout.fromValueOrUnknown(value.getEventType()))
                .signatureValid(value.isSignatureValid())
                .status(value.getStatus()).providerCheckoutId(value.getProviderCheckoutId()).failureReason(value.getFailureReason()).createdAt(value.getCreatedAt()).build()).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/grant")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> grant(@Valid @RequestBody ManualSubscriptionGrantRequest request) {
        return ResponseEntity.ok(ApiResponse.created(subscriptionService.grant(request), "Subscription manually granted"));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable String id, @Valid @RequestBody FinancialReasonRequest request) {
        subscriptionService.revoke(id, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Subscription revoked"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String id, @Valid @RequestBody FinancialReasonRequest request) {
        subscriptionService.cancel(id, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Subscription canceled"));
    }

    @PostMapping("/{id}/correct-state")
    public ResponseEntity<ApiResponse<Void>> correctSubscriptionState(@PathVariable String id, @Valid @RequestBody SubscriptionStateCorrectionRequest request) {
        subscriptionService.correctSubscription(id, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Subscription state corrected"));
    }

    @PostMapping("/payments/{id}/correct-state")
    public ResponseEntity<ApiResponse<Void>> correctPaymentState(@PathVariable String id, @Valid @RequestBody PaymentStateCorrectionRequest request) {
        subscriptionService.correctPayment(id, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Payment state corrected"));
    }
}
