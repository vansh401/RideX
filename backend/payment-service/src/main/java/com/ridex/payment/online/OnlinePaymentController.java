package com.ridex.payment.online;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Online payments. Who may call what is decided in SecurityConfig (riders for orders and verification, nobody's token for the webhook: it is signed). */
@RestController
public class OnlinePaymentController {

    public record OrderRequest(@NotBlank String purpose, BigDecimal amount, UUID rideId) {
    }

    public record VerifyRequest(@NotBlank String orderId, @NotBlank String paymentId, @NotNull String signature) {
    }

    private final OnlinePaymentService service;

    public OnlinePaymentController(OnlinePaymentService service) {
        this.service = service;
    }

    /** Which provider is active and the PUBLIC key the browser needs. MOCK means "no real payments". */
    @GetMapping("/api/payments/online/config")
    public Map<String, Object> config() {
        return Map.of("provider", service.provider().name(), "publicKey", service.provider().publicKey());
    }

    @PostMapping("/api/payments/online/orders")
    public OnlinePaymentService.Order order(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody OrderRequest request) {
        return service.createOrder(principal.id(), request.purpose(), request.amount(), request.rideId());
    }

    @PostMapping("/api/payments/online/verify")
    public OnlinePaymentService.Confirmation verify(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody VerifyRequest request) {
        return service.confirm(principal.id(), request.orderId(), request.paymentId(), request.signature());
    }

    /** Called by the provider, not by a person: no login, but the signature header proves who sent it. */
    @PostMapping("/api/payments/online/webhook")
    public Map<String, String> webhook(@RequestBody String body, @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        service.onWebhook(body, signature);
        return Map.of("status", "ok");
    }
}
