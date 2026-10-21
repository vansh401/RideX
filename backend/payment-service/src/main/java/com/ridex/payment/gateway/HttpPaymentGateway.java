package com.ridex.payment.gateway;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * A call to a REMOTE payment gateway over HTTP, protected so that the gateway having a bad day cannot take this service
 * (and everything waiting on it) down with it. Four protections, from the inside out:
 *
 *  TIMEOUT          A call that takes longer than {@code readTimeoutMs} is abandoned. Without it a hung gateway would hold
 *                   a thread for ever, and a few hung calls would use up all the threads.
 *  BULKHEAD         At most N calls to the gateway at the same time; the rest are refused immediately. Named after the
 *                   watertight walls in a ship: a flood in one compartment (the gateway) does not sink the whole ship
 *                   (this service). It stops a slow gateway from consuming every thread.
 *  CIRCUIT BREAKER  CLOSED = calls flow. When too many recent calls failed it OPENS: calls are refused instantly for a
 *                   while, without even trying, which gives the gateway room to recover and spares us the timeouts.
 *                   After the wait it goes HALF-OPEN: a few trial calls; if they work it CLOSES again.
 *  RETRY            A failed call is tried again after a short pause (for transient glitches). Safe ONLY because every
 *                   charge carries an idempotency key.
 *
 * What counts as a failure: a timeout, a connection error or a 5xx answer. A DECLINE ("card refused") is a normal answer
 * and is neither retried nor counted against the circuit.
 *
 * Every failure to get an answer ends as a {@link GatewayUnavailableException}; the caller then knows the outcome is
 * UNKNOWN and keeps the payment PENDING rather than guessing.
 */
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    /** What the gateway answers. */
    record ChargeResponse(boolean success, String reference, String failureReason) {
    }

    /** What we send. */
    record ChargeBody(BigDecimal amount, String currency, String description) {
    }

    private final RestClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    public HttpPaymentGateway(RestClient client, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead) {
        this.client = client;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        try {
            // Each decorator wraps the previous one: retry is outermost, then the circuit breaker, then the bulkhead
            Supplier<ChargeResponse> supplier = () -> call(request);
            supplier = Bulkhead.decorateSupplier(bulkhead, supplier);
            supplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
            supplier = Retry.decorateSupplier(retry, supplier);
            ChargeResponse response = supplier.get();
            return new ChargeResult(response.success(), response.reference(), response.failureReason());
        } catch (CallNotPermittedException e) {
            throw new GatewayUnavailableException("The payment gateway circuit is open: not even trying", e);
        } catch (BulkheadFullException e) {
            throw new GatewayUnavailableException("Too many calls to the payment gateway at the same time", e);
        } catch (GatewayCallException e) {
            throw new GatewayUnavailableException("The payment gateway did not answer: " + e.getMessage(), e);
        }
    }

    private ChargeResponse call(ChargeRequest request) {
        try {
            ChargeResponse response = client.post()
                    .uri("/charges")
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(new ChargeBody(request.amount(), request.currency(), request.description()))
                    .retrieve()
                    .body(ChargeResponse.class);
            if (response == null) {
                throw new GatewayCallException("empty answer", null);
            }
            return response;
        } catch (ResourceAccessException e) {
            // connection refused, connection reset, read timeout...
            log.debug("Gateway call failed: {}", e.getClass().getSimpleName());
            throw new GatewayCallException("no connection or timed out", e);
        } catch (HttpServerErrorException e) {
            throw new GatewayCallException("gateway error " + e.getStatusCode().value(), e);
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public Bulkhead bulkhead() {
        return bulkhead;
    }

    public static Duration millis(long ms) {
        return Duration.ofMillis(ms);
    }
}
