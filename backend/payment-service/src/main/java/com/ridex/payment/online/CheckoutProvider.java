package com.ridex.payment.online;

import java.math.BigDecimal;

/**
 * An online payment provider that works with a CHECKOUT: our server creates an ORDER, the rider's browser pays it (card, UPI, netbanking)
 * in the provider's own window, and the provider tells us the result with a SIGNATURE only it can produce. Our server never sees card
 * numbers, so we have almost no card-security burden (PCI scope).
 *
 * Two implementations: {@link RazorpayProvider} (real; free test mode with test keys) and {@link MockCheckoutProvider} (no keys needed;
 * used for development and tests). Which one runs is decided by whether Razorpay keys are configured.
 */
public interface CheckoutProvider {

    /** What the browser needs to open the checkout. */
    record ProviderOrder(String orderId) {
    }

    /** RAZORPAY or MOCK. */
    String name();

    /** The PUBLIC key id the browser needs (never the secret). */
    String publicKey();

    /** A mock provider accepts only fake payments; a real one cannot be "charged" from our server. */
    boolean canChargeFromServer();

    ProviderOrder createOrder(BigDecimal amount, String currency, String receipt);

    /** Did the provider really confirm this payment? (HMAC over order id and payment id.) */
    boolean verify(String orderId, String paymentId, String signature);

    /** Did the provider really send this webhook? (HMAC over the raw body.) */
    boolean verifyWebhook(String rawBody, String signature);
}
