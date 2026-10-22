package com.ridex.payment.online;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The provider used when no Razorpay keys are configured: development, tests, demos. The browser shows a "test checkout" window and
 * sends a fake payment id ("pay_mock_...") with the fixed signature below. It is only ever the active provider when NO real keys exist,
 * so it can never run next to real money.
 */
public class MockCheckoutProvider implements CheckoutProvider {

    public static final String SIGNATURE = "mock-signature";

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public String publicKey() {
        return "mock";
    }

    @Override
    public boolean canChargeFromServer() {
        return true;
    }

    @Override
    public ProviderOrder createOrder(BigDecimal amount, String currency, String receipt) {
        return new ProviderOrder("order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
    }

    @Override
    public boolean verify(String orderId, String paymentId, String signature) {
        return orderId != null && orderId.startsWith("order_mock_") && paymentId != null && paymentId.startsWith("pay_mock_")
                && SIGNATURE.equals(signature);
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return SIGNATURE.equals(signature);
    }
}
