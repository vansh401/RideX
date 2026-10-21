package com.ridex.ridex_app.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.platform.contracts.Topics;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * The core's half of the ride-payment saga, against real PostgreSQL and Kafka. The tests play the part of the payment service
 * by publishing the events it would publish.
 */
class PaymentSagaIntegrationTest extends AbstractEventTest {

    private static String paymentEvent(UUID eventId, String type, UUID rideId, UUID riderId) {
        boolean ok = type.endsWith("succeeded");
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"paymentId\":\"" + UUID.randomUUID() + "\",\"rideId\":\"" + rideId
                + "\",\"riderUserId\":\"" + riderId + "\",\"driverUserId\":\"" + UUID.randomUUID() + "\",\"amount\":252.00,\"currency\":\"INR\","
                + "\"status\":\"" + (ok ? "SUCCEEDED" : "FAILED") + "\",\"failureReason\":" + (ok ? "null" : "\"card_declined\"")
                + ",\"attempts\":1,\"gatewayReference\":null}}";
    }

    private boolean blocked(UUID rider) {
        return count("select count(*) from rider_payment_blocks where rider_user_id = ?", rider) > 0;
    }

    private Response book(Person rider, double lat, double lng) {
        return api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng));
    }

    @Test
    void aFailedPaymentBlocksTheRiderFromBookingUntilItIsSettled() {
        Person admin = newAdmin();
        newOnlineDriver(admin, VehicleCategory.CAB, 41.009, 70.0);
        Person rider = newRider();
        UUID unpaidRide = UUID.randomUUID();

        // COMPENSATION: the payment service says the charge failed
        publish(Topics.PAYMENT_EVENTS, unpaidRide.toString(), paymentEvent(UUID.randomUUID(), "payment.failed", unpaidRide, rider.user().getId()));
        assertThat(awaitTrue(() -> blocked(rider.user().getId()), 40)).isTrue();

        Response refused = book(rider, 41.0, 70.0);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.<String>json("$.message")).contains(unpaidRide.toString()).contains("settle");

        // the rider pays (retry succeeds): the block is lifted
        publish(Topics.PAYMENT_EVENTS, unpaidRide.toString(), paymentEvent(UUID.randomUUID(), "payment.succeeded", unpaidRide, rider.user().getId()));
        assertThat(awaitTrue(() -> !blocked(rider.user().getId()), 40)).isTrue();

        expect(201, book(rider, 41.0, 70.0));
    }

    @Test
    void otherRidersAreNotAffected() {
        Person admin = newAdmin();
        newOnlineDriver(admin, VehicleCategory.CAB, 42.009, 70.0);
        Person debtor = newRider();
        Person innocent = newRider();
        UUID ride = UUID.randomUUID();

        publish(Topics.PAYMENT_EVENTS, ride.toString(), paymentEvent(UUID.randomUUID(), "payment.failed", ride, debtor.user().getId()));
        assertThat(awaitTrue(() -> blocked(debtor.user().getId()), 40)).isTrue();

        expect(201, book(innocent, 42.0, 70.0));
    }

    @Test
    void payingForADifferentRideDoesNotReleaseTheBlock() {
        Person rider = newRider();
        UUID unpaid = UUID.randomUUID(), other = UUID.randomUUID();

        publish(Topics.PAYMENT_EVENTS, unpaid.toString(), paymentEvent(UUID.randomUUID(), "payment.failed", unpaid, rider.user().getId()));
        assertThat(awaitTrue(() -> blocked(rider.user().getId()), 40)).isTrue();
        publish(Topics.PAYMENT_EVENTS, other.toString(), paymentEvent(UUID.randomUUID(), "payment.succeeded", other, rider.user().getId()));
        UUID marker = UUID.randomUUID();
        publish(Topics.PAYMENT_EVENTS, marker.toString(), paymentEvent(UUID.randomUUID(), "payment.succeeded", marker, UUID.randomUUID()));
        awaitTrue(() -> count("select count(*) from processed_events where consumer_group = 'ride-payment-saga'") >= 3, 40);

        assertThat(blocked(rider.user().getId())).as("the unpaid ride is still unpaid").isTrue();
    }

    @Test
    void theSameEventTwiceChangesNothingTheSecondTime() {
        Person rider = newRider();
        UUID ride = UUID.randomUUID(), eventId = UUID.randomUUID();
        String failed = paymentEvent(eventId, "payment.failed", ride, rider.user().getId());

        publish(Topics.PAYMENT_EVENTS, ride.toString(), failed);
        publish(Topics.PAYMENT_EVENTS, ride.toString(), failed);
        assertThat(awaitTrue(() -> blocked(rider.user().getId()), 40)).isTrue();
        awaitTrue(() -> false, 2);

        assertThat(count("select count(*) from rider_payment_blocks where rider_user_id = ?", rider.user().getId())).isEqualTo(1);
        assertThat(count("select count(*) from processed_events where consumer_group = 'ride-payment-saga' and event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    void anUnrelatedEventTypeIsIgnored() {
        UUID ride = UUID.randomUUID();
        int before = count("select count(*) from rider_payment_blocks");

        publish(Topics.PAYMENT_EVENTS, ride.toString(), "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.refunded\",\"version\":1,"
                + "\"occurredAt\":\"" + Instant.now() + "\",\"key\":\"" + ride + "\",\"data\":{}}");
        awaitTrue(() -> false, 3);

        assertThat(count("select count(*) from rider_payment_blocks")).isEqualTo(before);
    }
}
