package com.ridex.ridex_app.events;

/** Kafka topic names, in one place. */
public final class Topics {

    private Topics() {
    }

    /** Every change of a ride's status. Key = ride id, so all events of one ride stay in order. */
    public static final String RIDE_EVENTS = "ride-events";

    /** Payment results. Key = ride id. */
    public static final String PAYMENT_EVENTS = "payment-events";

    /** Messages that failed for good land in "<topic>.DLT" (dead-letter topic). */
    public static String deadLetterOf(String topic) {
        return topic + ".DLT";
    }
}
