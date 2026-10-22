package com.ridex.payment.online;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * An online payment provider that works with a CHECKOUT: our server creates an ORDER, the rider's browser pays it (card, UPI, netbanking)
 * on the provider's own page or window, and the provider tells us the result in a way only it can produce (a signature, or an answer to
 * our own server-to-server question). Our server never sees card numbers, so we have almost no card-security burden (PCI scope).
 *
 * Implementations: {@link StripeProvider} and {@link RazorpayProvider} (real; both have a free test mode) and {@link MockCheckoutProvider}
 * (no keys needed; development and tests). Which one runs is decided by which keys are configured (see OnlineConfig).
 */
public interface CheckoutProvider {

    /**
     * What the browser needs to pay.
     *
     * @param checkoutUrl set when the provider hosts a PAGE (Stripe): the browser is sent there and returns afterwards.
     *                    null when the provider opens a window inside our page (Razorpay) or is the test checkout.
     */
    record ProviderOrder(String orderId, String checkoutUrl) {
        public ProviderOrder(String orderId) {
            this(orderId, null);
        }
    }

    /** A payment the provider has told us is complete (from a webhook). */
    record PaidNotice(String orderId, String paymentId) {
    }

    /** STRIPE, RAZORPAY or MOCK. */
    String name();

    /** The PUBLIC key the browser may need (never a secret). */
    String publicKey();

    /** A mock provider accepts only fake payments; a real one cannot be "charged" from our server. */
    boolean canChargeFromServer();

    /**
     * @param returnPath where in our website the rider lands after a hosted checkout, for example "/rider/wallet" or "/rides/{id}"
     */
    ProviderOrder createOrder(BigDecimal amount, String currency, String receipt, String returnPath);

    default ProviderOrder createOrder(BigDecimal amount, String currency, String receipt) {
        return createOrder(amount, currency, receipt, "/rider/wallet");
    }

    /**
     * Did the provider really confirm this payment? For Razorpay: an HMAC over order id and payment id. For Stripe: our own server asks Stripe
     * whether the checkout session is paid (a question only Stripe can answer, with our secret key).
     */
    boolean verify(String orderId, String paymentId, String signature);

    /**
     * The provider's server calls us when a payment completes. Returns the payment when the signature is valid AND the event means "paid";
     * empty for any other (valid) event; throws BadRequestException for a signature that does not check out.
     */
    Optional<PaidNotice> paidFromWebhook(String rawBody, String signatureHeader);
}
