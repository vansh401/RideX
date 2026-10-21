package com.ridex.payment;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.kafka.OutboxStore;
import com.ridex.platform.security.ServicePrincipal;
import com.ridex.platform.web.PageResponse;

/** Who may call what is decided in SecurityConfig (URL rules); the service narrows it further to the people on the ride. */
@RestController
public class PaymentController {

    private final PaymentService service;
    private final OutboxStore outbox;

    public PaymentController(PaymentService service, OutboxStore outbox) {
        this.service = service;
        this.outbox = outbox;
    }

    /** 404 until the payment exists: it is created a moment after the ride completes, by an event consumer. */
    @GetMapping("/api/payments/rides/{rideId}")
    public PaymentResponse forRide(@AuthenticationPrincipal ServicePrincipal principal, @PathVariable UUID rideId) {
        return service.forRide(principal.id(), principal.isAdmin(), rideId);
    }

    /** 409 unless the payment FAILED. */
    @PostMapping("/api/payments/rides/{rideId}/retry")
    public PaymentResponse retry(@AuthenticationPrincipal ServicePrincipal principal, @PathVariable UUID rideId) {
        return service.retry(principal.id(), rideId);
    }

    @GetMapping("/api/admin/payments")
    public PageResponse<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    /** Events saved but not yet published to Kafka. Normally 0 or very small. */
    @GetMapping("/api/admin/payments/outbox")
    public Map<String, Long> outbox() {
        return Map.of("pending", outbox.countUnpublished());
    }
}
