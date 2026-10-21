package com.ridex.ridex_app.pricing;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.ridex.platform.web.ServiceUnavailableException;
import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.observation.ObservationRegistry;

/**
 * A SYNCHRONOUS call to the pricing service (REST), because the rider is waiting for the answer: a ride cannot be created
 * without knowing what it costs. (Compare payment, which is ASYNCHRONOUS: nobody waits for it, so Kafka fits better.)
 *
 * Because the network is unreliable, the call is protected:
 *  TIMEOUT          give up after readTimeoutMs instead of hanging the rider's request.
 *  RETRY            try again once or twice for a glitch. Safe: asking a price is read-only (idempotent by nature).
 *  CIRCUIT BREAKER  if the pricing service keeps failing, stop calling it for a while and fail fast with a clear 503
 *                   instead of making every rider wait for a timeout.
 *
 * What it does NOT do is invent a price when the service is down: charging riders a guessed fare would be worse than saying
 * "try again in a moment". That is the honest answer to "what if pricing is down?".
 */
@Component
public class HttpPricingClient implements PricingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPricingClient.class);
    private static final String UNAVAILABLE = "Pricing is temporarily unavailable. Please try again in a moment";

    record QuoteRequest(LatLng pickup, LatLng dropoff, VehicleCategory category) {
    }

    private final RestClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String serviceToken;
    private final long retryAfterSeconds;

    public HttpPricingClient(PricingProperties p, ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(p.connectTimeoutMs())).build());
        factory.setReadTimeout(Duration.ofMillis(p.readTimeoutMs()));
        // observationRegistry: the call becomes a span and carries the "traceparent" header, so the pricing service continues the same trace
        this.client = RestClient.builder().baseUrl(p.baseUrl()).requestFactory(factory).observationRegistry(observations).build();
        this.circuitBreaker = CircuitBreaker.of("pricing", CircuitBreakerConfig.custom()
                .slidingWindowSize(p.circuitWindow())
                .minimumNumberOfCalls(p.circuitMinCalls())
                .failureRateThreshold(p.circuitFailureRate())
                .waitDurationInOpenState(Duration.ofMillis(p.circuitOpenMs()))
                // A 400 from pricing ("too close together") is a correct answer, not a failure of the service
                .recordExceptions(PricingUnavailable.class)
                .build());
        this.retry = Retry.of("pricing", RetryConfig.custom()
                .maxAttempts(p.retryMaxAttempts())
                .waitDuration(Duration.ofMillis(p.retryWaitMs()))
                .retryExceptions(PricingUnavailable.class)
                .build());
        this.serviceToken = p.serviceToken();
        this.retryAfterSeconds = Math.max(1, p.circuitOpenMs() / 1000);
    }

    /** Internal marker: the call failed in a way that may be temporary. */
    private static final class PricingUnavailable extends RuntimeException {
        PricingUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public FareQuote quote(LatLng pickup, LatLng dropoff, VehicleCategory category) {
        Supplier<FareQuote> call = () -> call(pickup, dropoff, category);
        call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        call = Retry.decorateSupplier(retry, call);
        try {
            return call.get();
        } catch (CallNotPermittedException e) {
            log.warn("Pricing circuit is open: not calling the pricing service");
            throw new ServiceUnavailableException(UNAVAILABLE, retryAfterSeconds);
        } catch (PricingUnavailable e) {
            log.warn("Pricing service did not answer: {}", e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE, retryAfterSeconds);
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private FareQuote call(LatLng pickup, LatLng dropoff, VehicleCategory category) {
        try {
            FareQuote quote = client.post()
                    .uri("/internal/quotes")
                    .header("X-Service-Token", serviceToken == null ? "" : serviceToken)
                    .body(new QuoteRequest(pickup, dropoff, category))
                    .retrieve()
                    .body(FareQuote.class);
            if (quote == null) {
                throw new PricingUnavailable("empty answer", null);
            }
            return quote;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400) {
                // The pricing service refused the TRIP ("too close together", "longer than 100 km"): show the rider why
                throw new BadRequestException(messageOf(e));
            }
            // 401/403: our own configuration is wrong (service token). Not the rider's fault, not worth retrying
            log.error("Pricing service rejected the credentials of the core ({})", e.getStatusCode().value());
            throw new ServiceUnavailableException(UNAVAILABLE, retryAfterSeconds);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PricingUnavailable(e.getClass().getSimpleName(), e);
        }
    }

    private static String messageOf(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        String marker = "\"message\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "The trip cannot be priced";
        }
        start += marker.length();
        int end = body.indexOf('"', start);
        return end < 0 ? "The trip cannot be priced" : body.substring(start, end);
    }
}
