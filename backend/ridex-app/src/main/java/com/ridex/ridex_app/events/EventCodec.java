package com.ridex.ridex_app.events;

import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Turns events into JSON text and back. A message that cannot be read becomes a {@link PoisonEventException}. */
@Component
public class EventCodec {

    private final JsonMapper mapper;

    public EventCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public EventEnvelope envelope(String type, String key, Object data) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, java.time.Instant.now(), key, mapper.valueToTree(data));
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
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException("The event data does not match " + type.getSimpleName(), e);
        }
    }
}
