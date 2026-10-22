package com.ridex.payment.online;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The signature checks that decide whether money is real. A fake key stands in; the maths is exactly what Razorpay documents. */
class RazorpayProviderTest {

    private final RazorpayProvider provider = new RazorpayProvider("http://localhost:1", "rzp_test_key", "secret-for-tests", "webhook-secret");

    @Test
    void aPaymentIsRealOnlyWhenTheSignatureIsTheHmacOfOrderAndPaymentIds() {
        String signature = RazorpayProvider.hmacHex("secret-for-tests", "order_ABC|pay_XYZ");

        assertThat(provider.verify("order_ABC", "pay_XYZ", signature)).isTrue();
        assertThat(provider.verify("order_ABC", "pay_OTHER", signature)).as("another payment id").isFalse();
        assertThat(provider.verify("order_OTHER", "pay_XYZ", signature)).as("another order id").isFalse();
        assertThat(provider.verify("order_ABC", "pay_XYZ", signature.toUpperCase())).as("not the same text").isFalse();
        assertThat(provider.verify("order_ABC", "pay_XYZ", null)).isFalse();
        assertThat(provider.verify("order_ABC", "pay_XYZ", RazorpayProvider.hmacHex("another-secret", "order_ABC|pay_XYZ"))).as("signed with a different key").isFalse();
    }

    @Test
    void aWebhookIsRealOnlyWhenSignedOverItsExactBodyWithTheWebhookSecret() {
        String body = "{\"event\":\"payment.captured\"}";
        String signature = RazorpayProvider.hmacHex("webhook-secret", body);

        assertThat(provider.verifyWebhook(body, signature)).isTrue();
        assertThat(provider.verifyWebhook(body + " ", signature)).as("one extra space changes everything").isFalse();
        assertThat(provider.verifyWebhook(body, RazorpayProvider.hmacHex("secret-for-tests", body))).as("the key secret is not the webhook secret").isFalse();
        assertThat(new RazorpayProvider("http://localhost:1", "k", "s", "").verifyWebhook(body, "anything"))
                .as("no webhook secret configured: every webhook is refused").isFalse();
    }

    @Test
    void theMockProviderOnlyAcceptsItsOwnFakePayments() {
        MockCheckoutProvider mock = new MockCheckoutProvider();
        String order = mock.createOrder(new java.math.BigDecimal("10"), "INR", "r").orderId();

        assertThat(mock.verify(order, "pay_mock_1", MockCheckoutProvider.SIGNATURE)).isTrue();
        assertThat(mock.verify(order, "pay_live_1", MockCheckoutProvider.SIGNATURE)).as("a real-looking payment id").isFalse();
        assertThat(mock.verify("order_realish", "pay_mock_1", MockCheckoutProvider.SIGNATURE)).isFalse();
        assertThat(provider.canChargeFromServer()).isFalse();
    }
}
