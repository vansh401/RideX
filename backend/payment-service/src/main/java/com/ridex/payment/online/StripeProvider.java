package com.ridex.payment.online;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe (https://stripe.com), using STRIPE CHECKOUT: Stripe hosts the payment page, so card details never touch our website or server.
 * Free to use in TEST MODE with test keys (no real money moves); the same code runs live with live keys.
 *
 *   createOrder  POST /v1/checkout/sessions  (server to server, Bearer secret key) returns a session id and the page's URL; the browser is sent there.
 *   verify       GET  /v1/checkout/sessions/{id}: our server asks Stripe whether that session is paid. Only Stripe can say "paid" for a
 *                session that our secret key created, so there is no signature for the browser to forge.
 *   webhook      Stripe-Signature header: "t=timestamp,v1=hmac". The HMAC-SHA256 of "timestamp.rawBody" with the webhook secret; the timestamp
 *                must be recent (replay protection).
 *
 * Secrets come from the environment and are never logged or sent to the browser. Amounts are in the smallest unit (paise for INR).
 */
public class StripeProvider implements CheckoutProvider {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final String publishableKey;
    private final String webhookSecret;
    private final String frontendBase;
    private final RestClient client;
    private final CircuitBreaker breaker;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public StripeProvider(String baseUrl, String secretKey, String publishableKey, String webhookSecret, String frontendBase) {
        this.publishableKey = publishableKey;
        this.webhookSecret = webhookSecret;
        this.frontendBase = frontendBase.endsWith("/") ? frontendBase.substring(0, frontendBase.length() - 1) : frontendBase;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).defaultHeaders(h -> h.setBearerAuth(secretKey)).build();
        // If Stripe keeps failing we stop calling it for a few seconds instead of making every rider wait for a timeout
        this.breaker = CircuitBreaker.of("stripe", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(RestClientException.class).build());
    }

    @Override
    public String name() {
        return "STRIPE";
    }

    @Override
    public String publicKey() {
        return publishableKey == null || publishableKey.isBlank() ? "stripe" : publishableKey;
    }

    @Override
    public boolean canChargeFromServer() {
        return false;
    }

    @Override
    public ProviderOrder createOrder(BigDecimal amount, String currency, String receipt, String returnPath) {
        long smallestUnit = amount.movePointRight(2).longValueExact();
        String path = returnPath == null || !returnPath.startsWith("/") ? "/rider/wallet" : returnPath;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", frontendBase + path + "?session_id={CHECKOUT_SESSION_ID}");
        form.add("cancel_url", frontendBase + path + "?payment=cancelled");
        form.add("client_reference_id", receipt);
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", currency.toLowerCase());
        form.add("line_items[0][price_data][unit_amount]", Long.toString(smallestUnit));
        form.add("line_items[0][price_data][product_data][name]", path.startsWith("/rides/") ? "RideX ride payment" : "RideX wallet top-up");
        try {
            Map<?, ?> session = breaker.executeSupplier(() -> client.post().uri("/v1/checkout/sessions").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class));
            Object id = session == null ? null : session.get("id");
            Object url = session == null ? null : session.get("url");
            if (id == null || url == null) {
                throw new ServiceUnavailableException("The payment provider gave an unexpected answer. Please try again", 5);
            }
            return new ProviderOrder(id.toString(), url.toString());
        } catch (RestClientException | CallNotPermittedException e) {
            throw new ServiceUnavailableException("The payment provider is not reachable right now. Please try again", 10);
        }
    }

    /** The browser came back from Stripe's page claiming it paid. We do not take its word: we ask Stripe. */
    @Override
    public boolean verify(String sessionId, String paymentId, String signature) {
        if (sessionId == null || !sessionId.startsWith("cs_")) {
            return false;
        }
        try {
            Map<?, ?> session = breaker.executeSupplier(() -> client.get().uri("/v1/checkout/sessions/{id}", sessionId).retrieve().body(Map.class));
            return session != null && "paid".equals(session.get("payment_status"));
        } catch (RestClientException | CallNotPermittedException e) {
            throw new ServiceUnavailableException("The payment provider is not reachable right now. Please try again", 10);
        }
    }

    @Override
    public Optional<PaidNotice> paidFromWebhook(String rawBody, String signatureHeader) {
        if (!signatureValid(rawBody, signatureHeader, Instant.now())) {
            throw new BadRequestException("Invalid webhook signature");
        }
        return WebhookEvents.stripe(mapper, rawBody);
    }

    /** "t=...,v1=...": valid when one v1 equals the HMAC of "t.body" and t is recent. Package-private so tests can fix the clock. */
    boolean signatureValid(String rawBody, String header, Instant now) {
        if (webhookSecret == null || webhookSecret.isBlank() || header == null) {
            return false;
        }
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) {
                timestamp = pair[1];
            } else if (pair.length == 2 && pair[0].equals("v1")) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        try {
            if (Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp)), now).abs().compareTo(TOLERANCE) > 0) {
                return false;   // too old (or from the future): a replayed webhook
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = RazorpayProvider.hmacHex(webhookSecret, timestamp + "." + rawBody);
        return signatures.stream().anyMatch(s -> RazorpayProvider.constantTimeEquals(expected, s));
    }

    /** Used by tests to build a valid header. */
    static String sign(String secret, long timestamp, String rawBody) {
        return "t=" + timestamp + ",v1=" + RazorpayProvider.hmacHex(secret, timestamp + "." + rawBody);
    }

}
