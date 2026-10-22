package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.Payment;
import com.ridex.payment.PaymentRepository;
import com.ridex.payment.PaymentStatus;
import com.ridex.platform.web.ResourceNotFoundException;

/** The receipt of a paid ride: what the rider was charged and how. Built from the payment itself, so it can never disagree with the books. */
@Service
public class ReceiptService {

    public record Receipt(String number, UUID rideId, Instant issuedAt, String currency, BigDecimal fare, BigDecimal discount, String couponCode,
                          BigDecimal paid, BigDecimal tip, BigDecimal refunded, String method, String status) {
    }

    private final PaymentRepository payments;
    private final TipService tips;

    public ReceiptService(PaymentRepository payments, TipService tips) {
        this.payments = payments;
        this.tips = tips;
    }

    /** The rider, the driver of that ride, or an administrator. Anyone else gets "not found". */
    @Transactional(readOnly = true)
    public Receipt forRide(UUID userId, boolean admin, UUID rideId) {
        Payment p = payments.findByRideId(rideId)
                .filter(x -> admin || x.getRiderUserId().equals(userId) || x.getDriverUserId().equals(userId))
                .filter(x -> x.getStatus() == PaymentStatus.SUCCEEDED)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        return new Receipt("RX-" + rideId.toString().substring(0, 8).toUpperCase(), rideId, p.getUpdatedAt(), p.getCurrency(), p.getAmount(),
                p.getDiscount(), p.getCouponCode(), p.netAmount(), tips.tipFor(rideId), p.getRefundedAmount(), p.getMethod(), p.getStatus().name());
    }
}
