package com.ridex.platform.contracts;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * The wrapper around every event, whatever it is about.
 *
 * - eventId: unique per event. Consumers use it to recognise a duplicate delivery.
 * - type: what happened ("ride.status-changed", "payment.succeeded").
 * - version: the shape of {@code data}. A consumer can refuse or adapt to a version it does not know, so the
 *   shape can evolve without breaking old consumers.
 * - key: the id of the thing the event is about (the ride id). Also the Kafka message key.
 * - data: the facts, as JSON.
 */
public record EventEnvelope(UUID eventId, String type, int version, Instant occurredAt, String key, JsonNode data) {

    public static final String RIDE_STATUS_CHANGED = "ride.status-changed";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String DRIVER_APPLICATION_REVIEWED = "driver.application-reviewed";
}
