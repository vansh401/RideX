package com.ridex.platform.kafka;

import java.time.Instant;
import java.util.UUID;

import com.ridex.platform.contracts.EventEnvelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Turns events into JSON text and back. A message that cannot be read becomes a {@link PoisonEventException}. */
public class EventCodec {

    private final JsonMapper mapper;

    /**
     * The mapper is adjusted to be a TOLERANT READER:
     *  - unknown fields are ignored (a producer may add fields at any time);
     *  - a missing number/boolean becomes its default instead of failing the whole event;
     *  - decimals keep their exact text (252.00 stays 252.00, never a binary double), which matters for money.
     */
    public EventCodec(JsonMapper mapper) {
        this.mapper = mapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    public EventEnvelope envelope(String type, String key, Object data) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), key, mapper.valueToTree(data));
    }

    public String toJson(EventEnvelope envelope) {
        return mapper.writeValueAsString(envelope);
    }

    public EventEnvelope parse(String json) {
        try {
            EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);
            if (envelope == null || envelope.eventId() == null || envelope.type() == null
                    || envelope.key() == null || envelope.data() == null) {
                throw new PoisonEventException("The event is missing required fields");
            }
            return envelope;
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PoisonEventException("The message is not a valid event", e);
        }
    }

    public <T> T data(EventEnvelope envelope, Class<T> type) {
        try {
            return mapper.convertValue(envelope.data(), type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PoisonEventException("The event data does not match " + type.getSimpleName(), e);
        }
    }
}
