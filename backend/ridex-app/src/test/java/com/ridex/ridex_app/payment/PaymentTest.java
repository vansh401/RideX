package com.ridex.ridex_app.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;

/** The payment state machine, with no database and no Kafka. */
class PaymentTest {

    private final UUID rideId = UUID.randomUUID();

    private Payment pending() {
        return Payment.start(rideId, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("250.00"), "INR", Instant.now());
    }

    @Test
    void aNewPaymentIsPendingOnItsFirstAttemptWithAKeyThatNamesTheRideAndTheAttempt() {
        Payment payment = pending();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAttempts()).isEqualTo(1);
        assertThat(payment.getIdempotencyKey()).isEqualTo("ride-" + rideId + "-attempt-1");
    }

    @Test
    void successIsFinalAndRepeatingItChangesNothing() {
        Payment payment = pending();

        payment.markSucceeded("ref-1", Instant.now());
        payment.markSucceeded("ref-2", Instant.now());   // a second "success" must not rewrite history

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayReference()).isEqualTo("ref-1");
    }

    @Test
    void aPaymentThatSucceededCanNeitherFailNorBeRetried() {
        Payment payment = pending();
        payment.markSucceeded("ref-1", Instant.now());

        assertThatThrownBy(() -> payment.markFailed("late decline", Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> payment.retry(Instant.now())).isInstanceOf(ConflictException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aFailedPaymentRecordsWhyAndCanBeRetriedWithANewKey() {
        Payment payment = pending();
        payment.markFailed("card_declined", Instant.now());
        assertThat(payment.getFailureReason()).isEqualTo("card_declined");

        payment.retry(Instant.now());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAttempts()).isEqualTo(2);
        assertThat(payment.getIdempotencyKey()).as("a NEW attempt must not reuse the old key, or the gateway would repeat the old answer")
                .isEqualTo("ride-" + rideId + "-attempt-2");
        assertThat(payment.getFailureReason()).isNull();
    }

    @Test
    void aPendingPaymentCannotBeRetried() {
        assertThatThrownBy(() -> pending().retry(Instant.now()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("failed");
    }

    @Test
    void theSameAttemptAlwaysProducesTheSameKey() {
        assertThat(Payment.keyFor(rideId, 3)).isEqualTo(Payment.keyFor(rideId, 3));
        assertThat(Payment.keyFor(rideId, 3)).isNotEqualTo(Payment.keyFor(rideId, 4));
    }
}
