package com.ridex.ridex_app.payment;

import java.math.BigDecimal;

/**
 * The outside world: the company that actually moves money (Stripe, Razorpay...). Our code only knows this
 * interface, so the mock below can be replaced by a real one without touching the payment logic.
 */
public interface PaymentGateway {

    /**
     * @param idempotencyKey the heart of safe payments: asking again with the SAME key must return the SAME result and
     *                       must never charge a second time. This is what makes a retry after a timeout safe.
     */
    record ChargeRequest(String idempotencyKey, BigDecimal amount, String currency, String description) {
    }

    record ChargeResult(boolean success, String reference, String failureReason) {
    }

    ChargeResult charge(ChargeRequest request);
}
