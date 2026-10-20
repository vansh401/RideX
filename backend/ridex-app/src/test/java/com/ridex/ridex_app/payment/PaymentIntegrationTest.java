package com.ridex.ridex_app.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.events.Topics;
import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** Payments through events, with the mock gateway: charged once, never twice, failures visible and retryable. */
class PaymentIntegrationTest extends AbstractEventTest {

    @Autowired
    private PaymentGateway gateway;

    private Response paymentOf(Person who, String rideId) {
        return api().get("/api/payments/rides/" + rideId, who.token());
    }

    private boolean paymentReaches(Person who, String rideId, String status) {
        return awaitTrue(() -> {
            Response r = paymentOf(who, rideId);
            return r.status() == 200 && status.equals(r.json("$.status"));
        }, 40);
    }

    /** A ride whose fare is above the mock gateway's limit, so the charge is declined. Returns the ride id. */
    private Journey completeExpensiveRide(double lat, double lng) {
        Person admin = newAdmin();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        // about 25 km: far above the 400 rupee limit configured for tests
        String body = json("{'category':'CAB','pickup':{'address':'Pickup','location':{'latitude':%s,'longitude':%s}},"
                + "'dropoff':{'address':'Far away','location':{'latitude':%s,'longitude':%s}}}",
                coord(lat), coord(lng), coord(lat + 0.20), coord(lng));
        String rideId = expect(201, api().post("/api/rides", rider.token(), body)).id();
        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/arrived", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/start", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/complete", driver.token(), null));
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
        return journey(rider, driver, rideId);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void aCompletedRideIsChargedOnceForItsFare() {
        Journey journey = completeRide(45.0, 70.0);
        String fare = expect(200, api().get("/api/rides/" + journey.rideId(), journey.rider().token())).json("$.fare.total").toString();

        assertThat(paymentReaches(journey.rider(), journey.rideId(), "SUCCEEDED")).isTrue();

        Response payment = paymentOf(journey.rider(), journey.rideId());
        assertThat(new BigDecimal(payment.json("$.amount").toString())).isEqualByComparingTo(fare);
        assertThat(payment.<String>json("$.currency")).isEqualTo("INR");
        assertThat(payment.<Integer>json("$.attempts")).isEqualTo(1);
        assertThat(payment.<String>json("$.gatewayReference")).startsWith("mock_ch_");
        // the gateway was asked exactly once
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key like ?", "ride-" + journey.rideId() + "-%"))
                .isEqualTo(1);
        assertThat(count("select count(*) from payments where ride_id = ?::uuid", journey.rideId())).isEqualTo(1);
    }

    @Test
    void thePaymentResultIsPublishedAsAnEventAndTheRiderAndDriverAreNotified() {
        Journey journey = completeRide(46.0, 70.0);
        assertThat(paymentReaches(journey.rider(), journey.rideId(), "SUCCEEDED")).isTrue();

        assertThat(awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ? and topic = ?",
                journey.rideId(), Topics.PAYMENT_EVENTS) == 1, 30)).isTrue();
        String event = jdbc.queryForObject("select payload from event_audit_log where event_key = ? and topic = ?", String.class,
                journey.rideId(), Topics.PAYMENT_EVENTS);
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("payment.succeeded");

        assertThat(awaitTrue(() -> api().get("/api/notifications?size=50", journey.rider().token())
                .<List<String>>json("$.content[*].type").contains("PAYMENT_RECEIVED"), 30)).isTrue();
        assertThat(awaitTrue(() -> api().get("/api/notifications?size=50", journey.driver().token())
                .<List<String>>json("$.content[*].type").contains("EARNING_CREDITED"), 30)).isTrue();
    }

    // ------------------------------------------------------------------ no double charging

    @Test
    void theCompletionEventDeliveredAgainDoesNotChargeAgain() {
        Journey journey = completeRide(47.0, 70.0);
        assertThat(paymentReaches(journey.rider(), journey.rideId(), "SUCCEEDED")).isTrue();
        String completed = auditedEvent(journey.rideId(), "RIDE_COMPLETED");

        publish(Topics.RIDE_EVENTS, journey.rideId(), completed);   // the identical message again (same event id)
        publish(Topics.RIDE_EVENTS, journey.rideId(), completed);
        assertThat(awaitCaughtUp(40)).isTrue();

        assertThat(count("select count(*) from payments where ride_id = ?::uuid", journey.rideId())).isEqualTo(1);
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key like ?", "ride-" + journey.rideId() + "-%"))
                .as("the gateway was not asked again").isEqualTo(1);
    }

    @Test
    void aSecondEventWithADifferentIdForTheSameRideDoesNotChargeAgainEither() {
        Journey journey = completeRide(48.0, 70.0);
        assertThat(paymentReaches(journey.rider(), journey.rideId(), "SUCCEEDED")).isTrue();
        String completed = auditedEvent(journey.rideId(), "RIDE_COMPLETED");
        String original = JsonPath.read(completed, "$.eventId");
        // A different event id defeats the "already handled" marker, so the NEXT layer must hold: one payment per ride
        String duplicateByAnotherProducer = completed.replace(original, UUID.randomUUID().toString());

        publish(Topics.RIDE_EVENTS, journey.rideId(), duplicateByAnotherProducer);
        assertThat(awaitCaughtUp(40)).isTrue();

        assertThat(count("select count(*) from payments where ride_id = ?::uuid", journey.rideId())).isEqualTo(1);
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key like ?", "ride-" + journey.rideId() + "-%")).isEqualTo(1);
        assertThat(count("select count(*) from event_audit_log where event_key = ? and topic = ?", journey.rideId(), Topics.PAYMENT_EVENTS))
                .as("and no second payment event").isEqualTo(1);
    }

    @Test
    void theGatewayReturnsTheOriginalResultForARepeatedIdempotencyKey() {
        String key = "test-key-" + UUID.randomUUID();
        PaymentGateway.ChargeRequest request = new PaymentGateway.ChargeRequest(key, new BigDecimal("120.00"), "INR", "test");

        PaymentGateway.ChargeResult first = gateway.charge(request);
        PaymentGateway.ChargeResult again = gateway.charge(request);
        PaymentGateway.ChargeResult withNewKey = gateway.charge(new PaymentGateway.ChargeRequest(key + "-2", new BigDecimal("120.00"), "INR", "test"));

        assertThat(first.success()).isTrue();
        assertThat(again).as("same key, same answer").isEqualTo(first);
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key = ?", key)).as("one charge, not two").isEqualTo(1);
        assertThat(withNewKey.reference()).as("a new key is a new charge").isNotEqualTo(first.reference());
    }

    @Test
    void theGatewayDeclinesAmountsAboveItsLimitAndRemembersTheDecline() {
        String key = "decline-" + UUID.randomUUID();
        PaymentGateway.ChargeRequest request = new PaymentGateway.ChargeRequest(key, new BigDecimal("9999.00"), "INR", "test");

        PaymentGateway.ChargeResult result = gateway.charge(request);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).startsWith("card_declined");
        assertThat(gateway.charge(request)).isEqualTo(result);
    }

    // ------------------------------------------------------------------ failure and retry

    @Test
    void aDeclinedChargeMarksThePaymentFailedAndTellsTheRider() {
        Journey journey = completeExpensiveRide(49.0, 70.0);

        assertThat(paymentReaches(journey.rider(), journey.rideId(), "FAILED")).isTrue();

        Response payment = paymentOf(journey.rider(), journey.rideId());
        assertThat(payment.<String>json("$.failureReason")).startsWith("card_declined");
        assertThat(awaitTrue(() -> api().get("/api/notifications?size=50", journey.rider().token())
                .<List<String>>json("$.content[*].type").contains("PAYMENT_FAILED"), 30)).isTrue();
        assertThat(awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ? and event_type = 'payment.failed'",
                journey.rideId()) == 1, 30)).isTrue();
    }

    @Test
    void aFailedPaymentCanBeRetriedWithANewIdempotencyKey() {
        Journey journey = completeExpensiveRide(54.0, 70.0);
        assertThat(paymentReaches(journey.rider(), journey.rideId(), "FAILED")).isTrue();

        Response retry = expect(200, api().post("/api/payments/rides/" + journey.rideId() + "/retry", journey.rider().token(), null));

        assertThat(retry.<Integer>json("$.attempts")).isEqualTo(2);
        assertThat(retry.<String>json("$.status")).as("still above the limit, so declined again").isEqualTo("FAILED");
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key like ?", "ride-" + journey.rideId() + "-%"))
                .as("attempt 1 and attempt 2 are two separate gateway calls").isEqualTo(2);
    }

    @Test
    void onlyTheRiderMayRetryAndOnlyAFailedPayment() {
        Journey failed = completeExpensiveRide(55.0, 70.0);
        Journey paid = completeRide(56.0, 70.0);
        assertThat(paymentReaches(failed.rider(), failed.rideId(), "FAILED")).isTrue();
        assertThat(paymentReaches(paid.rider(), paid.rideId(), "SUCCEEDED")).isTrue();
        Person stranger = newRider();

        assertThat(api().post("/api/payments/rides/" + failed.rideId() + "/retry", stranger.token(), null).status())
                .as("someone else's payment looks like it does not exist").isEqualTo(404);
        assertThat(api().post("/api/payments/rides/" + failed.rideId() + "/retry", failed.driver().token(), null).status())
                .as("a driver cannot retry").isEqualTo(403);
        assertThat(api().post("/api/payments/rides/" + paid.rideId() + "/retry", paid.rider().token(), null).status())
                .as("a payment that succeeded can never be charged again").isEqualTo(409);
    }

    // ------------------------------------------------------------------ who may see a payment

    @Test
    void onlyTheRiderTheDriverAndAdminsMaySeeAPayment() {
        Journey journey = completeRide(57.0, 70.0);
        assertThat(paymentReaches(journey.rider(), journey.rideId(), "SUCCEEDED")).isTrue();
        Person stranger = newRider();
        Person admin = newAdmin();

        assertThat(paymentOf(journey.rider(), journey.rideId()).status()).isEqualTo(200);
        assertThat(api().get("/api/payments/rides/" + journey.rideId(), journey.driver().token()).status()).isEqualTo(200);
        assertThat(paymentOf(admin, journey.rideId()).status()).isEqualTo(200);
        assertThat(paymentOf(stranger, journey.rideId()).status()).isEqualTo(404);
        assertThat(api().get("/api/payments/rides/" + journey.rideId(), null).status()).isEqualTo(401);
    }

    @Test
    void adminsCanListPaymentsAndFilterByStatus() {
        Journey paid = completeRide(58.0, 70.0);
        Person admin = newAdmin();
        assertThat(paymentReaches(paid.rider(), paid.rideId(), "SUCCEEDED")).isTrue();

        Response all = expect(200, api().get("/api/admin/payments?size=100", admin.token()));
        assertThat(all.<List<String>>json("$.content[*].rideId")).contains(paid.rideId());
        Response failed = expect(200, api().get("/api/admin/payments?status=FAILED&size=100", admin.token()));
        assertThat(failed.<List<String>>json("$.content[*].rideId")).doesNotContain(paid.rideId());
        assertThat(api().get("/api/admin/payments", paid.rider().token()).status()).isEqualTo(403);
    }

    @Test
    void aRideThatIsNotCompletedHasNoPaymentYet() {
        Person admin = newAdmin();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, 59.0, 70.0);
        Person rider = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", 59.0, 70.0))).id();

        assertThat(paymentOf(rider, rideId).status()).isEqualTo(404);

        api().post("/api/rides/" + rideId + "/cancel", rider.token(), null);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }
}
