package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ridex.payment.gateway.MockGateway;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.TestTokens;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The payment service end to end: a completed ride arrives as an event; the service charges through a REAL HTTP call to the
 * mock gateway, protected by timeout / retry / circuit breaker / bulkhead, and records the outcome.
 */
class PaymentFlowIntegrationTest extends PaymentTestBase {

    private static final BigDecimal FARE = new BigDecimal("252.00");

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    private void complete(UUID eventId, UUID rideId, UUID rider, UUID driver, BigDecimal fare) {
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(eventId, rideId, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER", fare));
    }

    private int chargesRecordedByGateway(UUID rideId) {
        return count("select count(*) from mock_gateway_charges c join payments p on p.idempotency_key = c.idempotency_key where p.ride_id = ?", rideId);
    }

    private int outboxEvents(UUID rideId, String type) {
        return count("select count(*) from outbox_events where event_key = ? and event_type = ?", rideId.toString(), type);
    }

    // ------------------------------------------------------------------ the happy path and "never twice"

    @Test
    void aCompletedRideIsChargedOnceAndTheResultIsPublished() {
        UUID ride = id(), rider = id(), driver = id();

        complete(id(), ride, rider, driver, FARE);

        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();
        assertThat(chargesRecordedByGateway(ride)).isEqualTo(1);
        assertThat(outboxEvents(ride, "payment.succeeded")).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceNeverChargesTwice() {
        UUID ride = id(), rider = id(), driver = id(), event = id();

        complete(event, ride, rider, driver, FARE);
        complete(event, ride, rider, driver, FARE);                 // exact duplicate (same event id)
        complete(id(), ride, rider, driver, FARE);                  // a different event for the same ride

        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();
        EventTestSupport.awaitTrue(() -> false, 3);                 // give the duplicates time to be (wrongly) processed
        assertThat(count("select count(*) from payments where ride_id = ?", ride)).isEqualTo(1);
        assertThat(chargesRecordedByGateway(ride)).isEqualTo(1);
        assertThat(outboxEvents(ride, "payment.succeeded")).isEqualTo(1);
    }

    @Test
    void otherRideTransitionsAreIgnored() {
        UUID ride = id();

        publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEvent(id(), ride, id(), id(), "DRIVER_ARRIVED", "RIDE_STARTED", "DRIVER", FARE));
        EventTestSupport.awaitTrue(() -> false, 3);

        assertThat(statusOf(ride)).isNull();
    }

    // ------------------------------------------------------------------ declines are answers, not outages

    @Test
    void aDeclineFailsThePaymentAtOnceAndTheRiderCanRetry() {
        UUID ride = id(), rider = id(), driver = id();

        complete(id(), ride, rider, driver, new BigDecimal("9000.00"));      // above the mock card's limit

        assertThat(awaitStatus(ride, "FAILED", 40)).isTrue();
        assertThat(outboxEvents(ride, "payment.failed")).isEqualTo(1);
        assertThat(circuitBreaker.getState()).as("a decline is a normal answer: it does not count against the circuit")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();

        // The rider retries: it is declined again, but it is a NEW attempt with a new idempotency key
        var retry = http.post("/api/payments/rides/" + ride + "/retry", TestTokens.token(rider, "RIDER"), null);
        assertThat(retry.status()).isEqualTo(200);
        assertThat((Integer) retry.json("$.attempts")).isEqualTo(2);
        assertThat(count("select count(*) from mock_gateway_charges c join payments p on p.ride_id = ? "
                + "where c.amount = p.amount", ride)).isEqualTo(2);
    }

    @Test
    void aSucceededPaymentCannotBeRetried() {
        UUID ride = id(), rider = id();
        complete(id(), ride, rider, id(), FARE);
        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();

        assertThat(http.post("/api/payments/rides/" + ride + "/retry", TestTokens.token(rider, "RIDER"), null).status()).isEqualTo(409);
        assertThat(chargesRecordedByGateway(ride)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the gateway misbehaves

    @Test
    void whenTheGatewayIsDownThePaymentWaitsPendingAndIsChargedOnceItRecovers() {
        UUID ride = id(), rider = id(), driver = id();
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));

        complete(id(), ride, rider, driver, FARE);

        assertThat(EventTestSupport.awaitTrue(() -> count("select count(*) from payments where ride_id = ? and gateway_outages >= 1", ride) == 1, 40)).isTrue();
        assertThat(statusOf(ride)).as("no answer is NOT a decline: the outcome is unknown, so it waits").isEqualTo("PENDING");
        assertThat(outboxEvents(ride, "payment.failed")).as("nobody is told it failed").isZero();

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));

        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();
        assertThat(chargesRecordedByGateway(ride)).isEqualTo(1);
    }

    @Test
    void aTimeoutAfterTheGatewayAlreadyChargedDoesNotChargeTwice() {
        UUID ride = id(), rider = id(), driver = id();
        // The gateway records the charge, then answers too late: we time out knowing nothing, but the money moved
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.SLOW_AFTER_PROCESSING, 2000));

        complete(id(), ride, rider, driver, FARE);

        assertThat(EventTestSupport.awaitTrue(() -> count("select count(*) from payments where ride_id = ? and gateway_outages >= 1", ride) == 1, 40)).isTrue();
        assertThat(statusOf(ride)).isEqualTo("PENDING");
        assertThat(chargesRecordedByGateway(ride)).as("the gateway DID charge, even though we saw a timeout").isEqualTo(1);

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));

        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();
        assertThat(chargesRecordedByGateway(ride)).as("asking again with the SAME idempotency key returned the original charge").isEqualTo(1);
    }

    @Test
    void anOutageThatNeverEndsEventuallyFailsThePayment() {
        UUID ride = id(), rider = id(), driver = id();
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));

        complete(id(), ride, rider, driver, FARE);

        assertThat(awaitStatus(ride, "FAILED", 60)).isTrue();
        assertThat(outboxEvents(ride, "payment.failed")).isEqualTo(1);
        assertThat(chargesRecordedByGateway(ride)).isZero();
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndLaterCallsAreRefusedWithoutReachingTheGateway() {
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));
        UUID rider = id(), driver = id();

        for (int i = 0; i < 4; i++) {
            complete(id(), id(), rider, driver, FARE);
        }

        assertThat(EventTestSupport.awaitTrue(() -> circuitBreaker.getState() == CircuitBreaker.State.OPEN, 40)).isTrue();
        int callsWhenOpened = mock.calls();
        EventTestSupport.awaitTrue(() -> false, 1);
        assertThat(mock.calls()).as("an open circuit does not even try").isLessThanOrEqualTo(callsWhenOpened + 2);
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isPositive();
    }

    @Test
    void theCircuitClosesAgainOnceTheGatewayHasRecovered() {
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));
        UUID rider = id(), driver = id();
        for (int i = 0; i < 4; i++) {
            complete(id(), id(), rider, driver, FARE);
        }
        assertThat(EventTestSupport.awaitTrue(() -> circuitBreaker.getState() == CircuitBreaker.State.OPEN, 40)).isTrue();

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));
        EventTestSupport.awaitTrue(() -> false, 2);   // let the open period (1.5 s in tests) pass
        UUID ride = id();
        complete(id(), ride, rider, driver, FARE);

        // After the open period one trial call is let through (HALF_OPEN); it works, so the circuit closes
        assertThat(awaitStatus(ride, "SUCCEEDED", 60)).isTrue();
        assertThat(EventTestSupport.awaitTrue(() -> circuitBreaker.getState() == CircuitBreaker.State.CLOSED, 10)).isTrue();
    }

    // ------------------------------------------------------------------ the REST API

    @Test
    void ridersAndDriversSeeTheirOwnPaymentAndStrangersDoNot() {
        UUID ride = id(), rider = id(), driver = id();
        complete(id(), ride, rider, driver, FARE);
        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();

        assertThat(http.get("/api/payments/rides/" + ride, TestTokens.token(rider, "RIDER")).status()).isEqualTo(200);
        assertThat(http.get("/api/payments/rides/" + ride, TestTokens.token(driver, "DRIVER")).status()).isEqualTo(200);
        assertThat(http.get("/api/payments/rides/" + ride, TestTokens.token(id(), "ADMIN")).status()).isEqualTo(200);
        assertThat(http.get("/api/payments/rides/" + ride, TestTokens.token(id(), "RIDER")).status()).as("someone else's ride").isEqualTo(404);
        assertThat(http.get("/api/payments/rides/" + id(), TestTokens.token(rider, "RIDER")).status()).isEqualTo(404);
    }

    @Test
    void tokensAreChecked() {
        UUID user = id();
        assertThat(http.get("/api/payments/rides/" + id(), null).status()).isEqualTo(401);
        assertThat(http.get("/api/payments/rides/" + id(), TestTokens.forgedToken(user, "RIDER")).status()).isEqualTo(401);
        assertThat(http.get("/api/payments/rides/" + id(), TestTokens.expiredToken(user, "RIDER")).status()).isEqualTo(401);
        assertThat(http.get("/api/payments/rides/" + id(), TestTokens.tokenFromAnotherIssuer(user, "RIDER")).status()).isEqualTo(401);
    }

    @Test
    void onlyAdminsUseTheAdminEndpointsAndOnlyRidersRetry() {
        UUID user = id();
        assertThat(http.get("/api/admin/payments", TestTokens.token(user, "RIDER")).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/payments", TestTokens.token(user, "ADMIN")).status()).isEqualTo(200);
        assertThat(http.get("/api/admin/payments/gateway", TestTokens.token(user, "DRIVER")).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/payments/gateway", TestTokens.token(user, "ADMIN")).status()).isEqualTo(200);
        assertThat(http.get("/api/admin/payments/outbox", TestTokens.token(user, "ADMIN")).status()).isEqualTo(200);
        assertThat(http.post("/api/payments/rides/" + id() + "/retry", TestTokens.token(user, "DRIVER"), null).status()).isEqualTo(403);
    }

    @Test
    void anAdminCanMakeTheGatewayMisbehaveAndSeeTheCircuit() {
        String admin = TestTokens.token(id(), "ADMIN");

        var set = http.post("/api/admin/payments/gateway/chaos", admin, "{\"mode\":\"ERROR\",\"delayMs\":0}");
        assertThat(set.status()).isEqualTo(200);
        assertThat(mock.chaos().mode()).isEqualTo(MockGateway.ChaosMode.ERROR);

        var status = http.get("/api/admin/payments/gateway", admin);
        assertThat((String) status.json("$.circuitState")).isEqualTo("CLOSED");
        assertThat((String) status.json("$.chaos.mode")).isEqualTo("ERROR");
    }

    @Test
    void theMockGatewayAnswersWithoutOurTokenLikeAnExternalSystemWould() {
        var response = http.post("/mock-gateway/charges", null, "{\"amount\":10,\"currency\":\"INR\",\"description\":\"x\"}");

        assertThat(response.status()).as("missing Idempotency-Key header").isEqualTo(400);
    }
}
