package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");

    private Payment payment() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "INR", now);
    }

    @Test
    void aNewPaymentIsPendingWithItsFirstIdempotencyKey() {
        Payment payment = payment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAttempts()).isEqualTo(1);
        assertThat(payment.getIdempotencyKey()).isNotBlank();
    }

    @Test
    void anOutageKeepsItPendingWithTheSameKeyUntilItGivesUp() {
        Payment payment = payment();
        String key = payment.getIdempotencyKey();

        assertThat(payment.recordGatewayOutage(now, Duration.ofSeconds(5), 3)).isFalse();
        assertThat(payment.recordGatewayOutage(now, Duration.ofSeconds(5), 3)).isFalse();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getIdempotencyKey()).as("the SAME key, so a lost response cannot cause a second charge").isEqualTo(key);

        assertThat(payment.recordGatewayOutage(now, Duration.ofSeconds(5), 3)).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void retryingNeedsAFailedPaymentAndUsesANewKey() {
        Payment payment = payment();
        assertThatThrownBy(() -> payment.retry(now)).isInstanceOf(RuntimeException.class);

        String firstKey = payment.getIdempotencyKey();
        payment.markFailed("card_declined", now);
        payment.retry(now);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAttempts()).isEqualTo(2);
        assertThat(payment.getIdempotencyKey()).isNotEqualTo(firstKey);
    }

    @Test
    void aSucceededPaymentCannotBeRetried() {
        Payment payment = payment();
        payment.markSucceeded("ref_1", now);

        assertThatThrownBy(() -> payment.retry(now)).isInstanceOf(RuntimeException.class);
    }
}
