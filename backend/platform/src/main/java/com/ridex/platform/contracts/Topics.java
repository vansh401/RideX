package com.ridex.platform.contracts;

/**
 * Kafka topic names: part of the PUBLISHED LANGUAGE between services. Changing one is a breaking change for every
 * consumer, so it is done like any API change (add the new one, migrate consumers, remove the old).
 */
public final class Topics {

    private Topics() {
    }

    /** Every change of a ride's status. Key = ride id. Published by the core. */
    public static final String RIDE_EVENTS = "ride-events";

    /** Payment results. Key = ride id. Published by the payment service. */
    public static final String PAYMENT_EVENTS = "payment-events";

    /** Driver application decisions. Key = driver user id. Published by the core. */
    public static final String DRIVER_EVENTS = "driver-events";

    /** Messages that failed for good land in "<topic>.DLT" (dead-letter topic). */
    public static String deadLetterOf(String topic) {
        return topic + ".DLT";
    }
}
