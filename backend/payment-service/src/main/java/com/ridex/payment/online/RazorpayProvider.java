package com.ridex.payment.online;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ridex.platform.web.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay (https://razorpay.com). Free to use in TEST MODE with test keys (no real money moves); the same code runs live with live keys.
 *
 * Three calls matter: create an order (REST, HTTP Basic with key id and secret), verify the signature of a finished payment
 * (HMAC-SHA256 of "orderId|paymentId" with the key secret), and verify a webhook (HMAC-SHA256 of the raw body with the webhook secret).
 * The key SECRET and the webhook secret come from the environment and are never logged or sent to the browser.
 */
public class RazorpayProvider implements CheckoutProvider {

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RestClient client;
    /** If Razorpay keeps failing we stop calling it for a few seconds instead of making every rider wait for a timeout. */
    private final CircuitBreaker breaker;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RazorpayProvider(String baseUrl, String keyId, String keySecret, String webhookSecret) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
        this.breaker = CircuitBreaker.of("razorpay", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(RestClientException.class).build());
    }

    @Override
    public String name() {
        return "RAZORPAY";
    }

    @Override
    public String publicKey() {
        return keyId;
    }

    @Override
    public boolean canChargeFromServer() {
        return false;
    }

    @Override
    public ProviderOrder createOrder(BigDecimal amount, String currency, String receipt, String returnPath) {
        long paise = amount.movePointRight(2).longValueExact();   // Razorpay counts in the smallest unit
        try {
            Map<?, ?> response = breaker.executeSupplier(() -> client.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", paise, "currency", currency, "receipt", receipt.length() > 40 ? receipt.substring(0, 40) : receipt))
                    .retrieve().body(Map.class));
            Object id = response == null ? null : response.get("id");
            if (id == null) {
                throw new ServiceUnavailableException("The payment provider gave an unexpected answer. Please try again", 5);
            }
            return new ProviderOrder(id.toString());
        } catch (RestClientException | CallNotPermittedException e) {
            throw new ServiceUnavailableException("The payment provider is not reachable right now. Please try again", 10);
        }
    }

    @Override
    public boolean verify(String orderId, String paymentId, String signature) {
        return constantTimeEquals(hmacHex(keySecret, orderId + "|" + paymentId), signature);
    }

    public boolean verifyWebhook(String rawBody, String signature) {
        return webhookSecret != null && !webhookSecret.isBlank() && constantTimeEquals(hmacHex(webhookSecret, rawBody), signature);
    }

    @Override
    public Optional<PaidNotice> paidFromWebhook(String rawBody, String signatureHeader) {
        if (!verifyWebhook(rawBody, signatureHeader)) {
            throw new com.ridex.platform.web.BadRequestException("Invalid webhook signature");
        }
        return WebhookEvents.razorpay(mapper, rawBody);
    }

    static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Comparing signatures must take the same time whatever matches, so an attacker cannot learn it byte by byte. */
    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
