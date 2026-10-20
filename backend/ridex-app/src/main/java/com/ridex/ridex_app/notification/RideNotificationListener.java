package com.ridex.ridex_app.notification;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.ride.RideStatusChanged;

/**
 * Decides WHO is told WHAT when a ride changes. This is the only place with notification wording for rides.
 * It runs after the ride transaction has committed, and the notification work happens in its own transaction.
 */
@Component
public class RideNotificationListener {

    private static final String RUPEE = "₹";

    private final NotificationService notifications;

    public RideNotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRideStatusChanged(RideStatusChanged e) {
        switch (e.to()) {
            case DRIVER_ASSIGNED -> {
                if (e.driverUserId() != null && e.offer() != null) {
                    notifications.notify(e.driverUserId(), NotificationType.RIDE_OFFER, "New ride request",
                            e.offer().pickupAddress() + " to " + e.offer().dropoffAddress()
                                    + " · " + money(e.offer().totalFare()), e.rideId());
                }
            }
            case DRIVER_ACCEPTED -> notifications.notify(e.riderUserId(), NotificationType.RIDE_ACCEPTED,
                    "Your driver is on the way", "A driver accepted your ride and is heading to your pickup.", e.rideId());
            case DRIVER_ARRIVED -> notifications.notify(e.riderUserId(), NotificationType.DRIVER_ARRIVED,
                    "Your driver has arrived", "Your driver is waiting at the pickup point.", e.rideId());
            case RIDE_STARTED -> notifications.notify(e.riderUserId(), NotificationType.TRIP_STARTED,
                    "Your trip has started", "Have a safe ride.", e.rideId());
            case RIDE_COMPLETED -> {
                notifications.notify(e.riderUserId(), NotificationType.TRIP_COMPLETED,
                        "Trip completed", "You have arrived. Please rate your driver.", e.rideId());
                if (e.driverUserId() != null) {
                    notifications.notify(e.driverUserId(), NotificationType.TRIP_COMPLETED,
                            "Trip completed", "Well done. Please rate your rider.", e.rideId());
                }
            }
            case CANCELLED -> cancelled(e);
            default -> {
                // REQUESTED / SEARCHING_DRIVER: nobody needs to be told
            }
        }
    }

    private void cancelled(RideStatusChanged e) {
        if (e.actor() == ActorRole.SYSTEM) {
            notifications.notify(e.riderUserId(), NotificationType.NO_DRIVER_FOUND,
                    "No driver available", "We could not find a driver nearby. Please try again.", e.rideId());
        } else if (e.actor() == ActorRole.DRIVER) {
            notifications.notify(e.riderUserId(), NotificationType.RIDE_CANCELLED,
                    "Your driver cancelled", "Your driver cancelled the ride. You can book again.", e.rideId());
        } else if (e.actor() == ActorRole.RIDER && e.driverUserId() != null && e.from() != RideStatus.DRIVER_ASSIGNED) {
            // A rider cancelling while the ride is only an unanswered offer needs no notification: the offer
            // simply disappears. Once a driver had accepted, they must be told.
            notifications.notify(e.driverUserId(), NotificationType.RIDE_CANCELLED,
                    "Ride cancelled", "The rider cancelled this ride.", e.rideId());
        }
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%s%.2f", RUPEE, amount);
    }
}
