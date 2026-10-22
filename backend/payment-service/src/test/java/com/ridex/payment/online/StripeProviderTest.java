package com.ridex.payment.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;

/**
 * The Stripe provider against a FAKE Stripe on localhost (JDK HttpServer): what we send, how we read the answers, and the webhook signature
 * rules. No account, no network, no real money. The fake follows Stripe's documented shapes.
 */
class StripeProviderTest {

    private static final String SECRET = "sk_test_unit";
    private static final String WEBHOOK_SECRET = "whsec_unit";

    private HttpServer fake;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int sessionStatus = 200;
    private volatile String sessionPaymentStatus = "paid";
    private StripeProvider provider;

    @BeforeEach
    void startFakeStripe() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/checkout/sessions", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = "POST".equals(exchange.getRequestMethod())
                    ? "{\"id\":\"cs_test_abc123\",\"url\":\"https://checkout.stripe.test/pay/cs_test_abc123\"}"
                    : "{\"id\":\"cs_test_abc123\",\"payment_status\":\"" + sessionPaymentStatus + "\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(sessionStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        provider = new StripeProvider("http://127.0.0.1:" + fake.getAddress().getPort(), SECRET, "pk_test_unit", WEBHOOK_SECRET, "https://ridex.test/");
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    // ------------------------------------------------------------------ creating the checkout

    @Test
    void anOrderBecomesAHostedCheckoutSessionInPaiseWithTheRightReturnAddresses() {
        CheckoutProvider.ProviderOrder order = provider.createOrder(new BigDecimal("250.50"), "INR", "rx_1234abcd", "/rides/42");

        assertThat(order.orderId()).isEqualTo("cs_test_abc123");
        assertThat(order.checkoutUrl()).startsWith("https://checkout.stripe.test/");
        assertThat(lastAuth.get()).as("the secret key travels only in the server-to-server header").isEqualTo("Bearer " + SECRET);
        String body = java.net.URLDecoder.decode(lastBody.get(), StandardCharsets.UTF_8);
        assertThat(body).contains("mode=payment").contains("line_items[0][price_data][currency]=inr").contains("line_items[0][price_data][unit_amount]=25050")
                .contains("client_reference_id=rx_1234abcd").contains("success_url=https://ridex.test/rides/42?session_id={CHECKOUT_SESSION_ID}")
                .contains("cancel_url=https://ridex.test/rides/42?payment=cancelled");
    }

    @Test
    void aReturnPathThatIsNotOurOwnIsReplacedSoTheRiderCannotBeSentElsewhere() {
        provider.createOrder(new BigDecimal("100"), "INR", "rx_1", "https://evil.example/steal");

        String body = java.net.URLDecoder.decode(lastBody.get(), StandardCharsets.UTF_8);
        assertThat(body).contains("success_url=https://ridex.test/rider/wallet?session_id=").doesNotContain("evil.example");
    }

    @Test
    void whenStripeCannotBeReachedTheRiderGetsAnHonestTryAgain() {
        fake.stop(0);

        assertThatThrownBy(() -> provider.createOrder(new BigDecimal("100"), "INR", "rx_1", "/rider/wallet")).isInstanceOf(ServiceUnavailableException.class);
    }

    // ------------------------------------------------------------------ asking Stripe whether it was paid

    @Test
    void aSessionIsPaidOnlyWhenStripeItselfSaysPaid() {
        assertThat(provider.verify("cs_test_abc123", "ignored", "")).isTrue();
        assertThat(lastPath.get()).isEqualTo("/v1/checkout/sessions/cs_test_abc123");

        sessionPaymentStatus = "unpaid";
        assertThat(provider.verify("cs_test_abc123", "ignored", "")).as("the rider came back without paying").isFalse();
    }

    @Test
    void anIdThatIsNotAStripeSessionIsRefusedWithoutAskingStripe() {
        lastPath.set(null);

        assertThat(provider.verify("order_mock_123", "pay", "x")).isFalse();
        assertThat(provider.verify(null, "pay", "x")).isFalse();
        assertThat(lastPath.get()).as("no request was made").isNull();
    }

    // ------------------------------------------------------------------ the webhook

    private static final String PAID_EVENT = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_abc123\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_999\"}}}";

    @Test
    void aValidlySignedPaidEventIsReadAsPaid() {
        String header = StripeProvider.sign(WEBHOOK_SECRET, Instant.now().getEpochSecond(), PAID_EVENT);

        Optional<CheckoutProvider.PaidNotice> paid = provider.paidFromWebhook(PAID_EVENT, header);

        assertThat(paid).contains(new CheckoutProvider.PaidNotice("cs_test_abc123", "pi_999"));
    }

    @Test
    void otherEventsAndUnpaidSessionsAreValidButMeanNothing() {
        String other = "{\"type\":\"charge.refunded\",\"data\":{\"object\":{\"id\":\"ch_1\"}}}";
        String unpaid = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_1\",\"payment_status\":\"unpaid\"}}}";
        long now = Instant.now().getEpochSecond();

        assertThat(provider.paidFromWebhook(other, StripeProvider.sign(WEBHOOK_SECRET, now, other))).isEmpty();
        assertThat(provider.paidFromWebhook(unpaid, StripeProvider.sign(WEBHOOK_SECRET, now, unpaid))).as("a delayed payment method: wait for the later event").isEmpty();
    }

    @Test
    void aForgedTamperedOrReplayedWebhookIsRefused() {
        long now = Instant.now().getEpochSecond();
        String good = StripeProvider.sign(WEBHOOK_SECRET, now, PAID_EVENT);

        assertThatThrownBy(() -> provider.paidFromWebhook(PAID_EVENT, StripeProvider.sign("whsec_other", now, PAID_EVENT))).as("another secret").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provider.paidFromWebhook(PAID_EVENT.replace("paid", "PAID"), good)).as("a changed body").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provider.paidFromWebhook(PAID_EVENT, StripeProvider.sign(WEBHOOK_SECRET, now - 3600, PAID_EVENT))).as("an hour old: a replay").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provider.paidFromWebhook(PAID_EVENT, null)).as("no header").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> provider.paidFromWebhook(PAID_EVENT, "garbage")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void withoutAWebhookSecretEveryWebhookIsRefused() {
        StripeProvider noSecret = new StripeProvider("http://127.0.0.1:1", SECRET, "", "", "https://ridex.test");
        String header = "t=" + Instant.now().getEpochSecond() + ",v1=00";

        assertThatThrownBy(() -> noSecret.paidFromWebhook(PAID_EVENT, header)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anyOfSeveralSignaturesMayMatchWhichIsHowStripeRotatesSecrets() {
        long now = Instant.now().getEpochSecond();
        String valid = StripeProvider.sign(WEBHOOK_SECRET, now, PAID_EVENT);
        String v1 = valid.substring(valid.indexOf("v1="));
        List<String> parts = new ArrayList<>(List.of("t=" + now, "v1=deadbeef", v1));

        assertThat(provider.signatureValid(PAID_EVENT, String.join(",", parts), Instant.now())).isTrue();
    }

    @Test
    void theProviderNamesItselfAndExposesOnlyThePublishableKey() {
        assertThat(provider.name()).isEqualTo("STRIPE");
        assertThat(provider.publicKey()).isEqualTo("pk_test_unit").doesNotContain(SECRET);
        assertThat(provider.canChargeFromServer()).isFalse();
    }
}
