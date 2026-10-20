package com.ridex.ridex_app.payment;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Payments", description = "Payment of a completed ride (mock gateway, no real money)")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/api/payments/rides/{rideId}")
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER', 'ADMIN')")
    @Operation(summary = "The payment of a ride",
            description = "404 until the payment exists: it is created a moment after the ride completes, by an event consumer.")
    public PaymentResponse forRide(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.forRide(principal.id(), ActorRole.valueOf(principal.role().name()), rideId);
    }

    @PostMapping("/api/payments/rides/{rideId}/retry")
    @PreAuthorize("hasRole('RIDER')")
    @Operation(summary = "Try a failed payment again", description = "409 unless the payment FAILED.")
    public PaymentResponse retry(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.retry(principal.id(), rideId);
    }

    @GetMapping("/api/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "All payments, newest first", description = "Optional status filter.")
    public PageResponse<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }
}
