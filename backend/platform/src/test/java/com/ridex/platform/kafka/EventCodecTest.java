package com.ridex.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;

import tools.jackson.databind.json.JsonMapper;

class EventCodecTest {

    private final EventCodec codec = new EventCodec(JsonMapper.builder().build());

    private static PaymentEventData payment() {
        return new PaymentEventData(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("252.00"),
                "INR", "SUCCEEDED", null, 1, "ref");
    }

    @Test
    void anEventSurvivesTheRoundTripWithMoneyIntact() {
        PaymentEventData original = payment();

        EventEnvelope read = codec.parse(codec.toJson(codec.envelope(EventEnvelope.PAYMENT_SUCCEEDED, "ride-1", original)));
        PaymentEventData back = codec.data(read, PaymentEventData.class);

        assertThat(read.type()).isEqualTo("payment.succeeded");
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.key()).isEqualTo("ride-1");
        assertThat(read.eventId()).isNotNull();
        assertThat(back).isEqualTo(original);
        assertThat(back.amount()).isEqualByComparingTo("252.00");
    }

    @Test
    void everyEventGetsItsOwnId() {
        EventEnvelope a = codec.envelope("t", "k", payment());
        EventEnvelope b = codec.envelope("t", "k", payment());

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }

    @Test
    void messagesThatAreNotEventsArePoisonNotRetriedForever() {
        assertThatThrownBy(() -> codec.parse("not json at all")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> codec.parse("{}")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> codec.parse("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"x\"}")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> codec.parse("null")).isInstanceOf(PoisonEventException.class);
    }

    @Test
    void dataOfTheWrongShapeIsPoisonToo() {
        EventEnvelope event = codec.parse("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.succeeded\",\"version\":1,"
                + "\"occurredAt\":\"2026-01-01T10:00:00Z\",\"key\":\"k\",\"data\":{\"rideId\":\"not-a-uuid\"}}");

        assertThatThrownBy(() -> codec.data(event, PaymentEventData.class)).isInstanceOf(PoisonEventException.class);
    }

    @Test
    void unknownFieldsAreIgnoredSoProducersCanAddFields() {
        EventEnvelope event = codec.parse("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.succeeded\",\"version\":1,"
                + "\"occurredAt\":\"2026-01-01T10:00:00Z\",\"key\":\"k\",\"data\":{\"rideId\":\"" + UUID.randomUUID()
                + "\",\"somethingNew\":123},\"alsoNew\":true}");

        assertThat(codec.data(event, PaymentEventData.class).rideId()).isNotNull();
    }
}
