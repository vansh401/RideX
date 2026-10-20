package com.ridex.ridex_app.realtime;

import java.util.UUID;

/**
 * "Send this to a browser." The rest of the application only knows this interface: it does not know
 * about WebSockets, STOMP or Redis. Delivery is best effort. If a browser is not connected the message
 * is simply lost, which is why anything that must not be lost is also stored (notifications) or can be
 * fetched again over REST (the ride itself).
 */
public interface RealtimePublisher {

    /**
     * To one user, on all of their open connections (several tabs, phone and laptop). The client
     * subscribes to {@code /user + destination}; for example destination "/queue/offers" is received
     * by subscribing to "/user/queue/offers".
     */
    void toUser(UUID userId, String destination, Object payload);

    /** To everyone subscribed to this topic (for example "/topic/rides/{id}/location"). */
    void toTopic(String destination, Object payload);
}
