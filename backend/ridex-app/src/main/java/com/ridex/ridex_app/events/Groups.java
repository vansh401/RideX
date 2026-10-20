package com.ridex.ridex_app.events;

import java.util.List;
import java.util.Map;

/**
 * Consumer groups. A GROUP is a set of consumers that share the work of reading a topic: each message goes to ONE
 * member of the group. Different groups are completely independent: each group gets every message and keeps its own
 * position (offset). That is how one event can notify users, update analytics, feed an audit log and trigger a
 * payment without those four things knowing about each other, and without one slow group delaying another.
 */
public final class Groups {

    private Groups() {
    }

    public static final String NOTIFICATIONS = "notifications";
    public static final String ANALYTICS = "analytics";
    public static final String AUDIT = "audit";
    public static final String PAYMENTS = "payments";
    public static final String DEAD_LETTERS = "dead-letters";

    /** Which topics each group reads (used to report consumer lag). */
    public static final Map<String, List<String>> TOPICS_BY_GROUP = Map.of(
            NOTIFICATIONS, List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS),
            ANALYTICS, List.of(Topics.RIDE_EVENTS),
            AUDIT, List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS),
            PAYMENTS, List.of(Topics.RIDE_EVENTS),
            DEAD_LETTERS, List.of(Topics.deadLetterOf(Topics.RIDE_EVENTS), Topics.deadLetterOf(Topics.PAYMENT_EVENTS)));
}
