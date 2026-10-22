package com.ridex.payment.refund;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;
import com.ridex.platform.web.BadRequestException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Administrators refund a ride by hand. (Refunds that come from a resolved complaint arrive as an event: see ComplaintRefundConsumer.) */
@RestController
public class RefundController {

    public record RefundRequest(@NotNull UUID rideId, BigDecimal amount, String reason, String destination) {
    }

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    /** The Idempotency-Key makes a double click, or a retried request, refund once. */
    @PostMapping("/api/admin/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundService.Refund refund(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody RefundRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("The Idempotency-Key header is required");
        }
        return refunds.request(request.rideId(), request.amount(), request.reason(), "ADMIN", principal.id().toString(),
                request.destination(), "admin-refund-" + idempotencyKey, null);
    }

    @GetMapping("/api/admin/refunds")
    public List<RefundService.Refund> list(@RequestParam(required = false) UUID rideId) {
        return refunds.latest(rideId);
    }
}
