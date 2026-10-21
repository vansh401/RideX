package com.ridex.analytics;

import java.util.List;
import java.util.Map;

import com.ridex.platform.contracts.Topics;

/**
 * Consumer groups of the whole platform (for the lag report) and the groups this service owns.
 *
 * A GROUP is a set of consumers that share the work of reading a topic: each message goes to ONE member. Different groups are
 * completely independent: each gets every message and keeps its own position (offset). That is how one event can notify users,
 * count statistics, feed an audit log and trigger a payment without those things knowing about each other.
 *
 * Group names are part of the contract between services (they identify a consumer's saved position), so they are listed
 * here by name and never imported from another service's code.
 */
public final class Groups {

    private Groups() {
    }

    // owned by this service
    public static final String ANALYTICS = "analytics";
    public static final String AUDIT = "audit";
    public static final String DEAD_LETTERS = "dead-letters";

    // owned by the other services
    public static final String NOTIFICATIONS = "notifications";
    public static final String PAYMENTS = "payments";
    public static final String RIDE_PAYMENT_SAGA = "ride-payment-saga";

    /** Which topics each group reads (used to report consumer lag). */
    public static final Map<String, List<String>> TOPICS_BY_GROUP = Map.of(
            NOTIFICATIONS, List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.DRIVER_EVENTS),
            ANALYTICS, List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS),
            AUDIT, List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.DRIVER_EVENTS),
            PAYMENTS, List.of(Topics.RIDE_EVENTS),
            RIDE_PAYMENT_SAGA, List.of(Topics.PAYMENT_EVENTS),
            DEAD_LETTERS, List.of(Topics.deadLetterOf(Topics.RIDE_EVENTS), Topics.deadLetterOf(Topics.PAYMENT_EVENTS),
                    Topics.deadLetterOf(Topics.DRIVER_EVENTS)));
}
