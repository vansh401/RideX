package com.ridex.payment.gateway;

import java.math.BigDecimal;

/**
 * The outside world: the company that actually moves money (Stripe, Razorpay...). Our code only knows this interface.
 *
 * Two very different kinds of "it did not work":
 *  - a DECLINE ("card refused") is a normal ANSWER: {@link ChargeResult#success()} is false. The rider must act.
 *  - an OUTAGE (timeout, 500, circuit open) is NO answer: {@link GatewayUnavailableException}. We do not know what
 *    happened, so we must try again later, safely.
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

    ChargeResult charge(ChargeRequest request) throws GatewayUnavailableException;
}
