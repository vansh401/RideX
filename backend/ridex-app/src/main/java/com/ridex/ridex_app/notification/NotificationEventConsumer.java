package com.ridex.ridex_app.notification;

import java.math.BigDecimal;
import java.util.Locale;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.events.EventCodec;
import com.ridex.ridex_app.events.EventEnvelope;
import com.ridex.ridex_app.events.Groups;
import com.ridex.ridex_app.events.PaymentEventData;
import com.ridex.ridex_app.events.ProcessedEvents;
import com.ridex.ridex_app.events.RideEventData;
import com.ridex.ridex_app.events.Topics;

/**
 * Creates notifications from events. This REPLACES the in-process listener of Phase 5 (and the idea of Step 2.7,
 * where one class called another directly).
 *
 * What changed, and why it is better:
 * - The ride code does not know notifications exist. It records "the ride changed" and is done.
 * - Notifications can be slow, fail, or be redeployed without slowing down or breaking a ride.
 * - If this consumer is down for an hour, the events wait in Kafka and nothing is lost; it catches up.
 * - The price: a notification now arrives a fraction of a second later (outbox, Kafka, consumer) instead of
 *   instantly. The live ride screen does not wait for it: that still uses the immediate WebSocket push.
 *
 * Each notification is saved in the same transaction as the "already handled" marker, so a redelivered event
 * never creates a second notification.
 */
@Component
public class NotificationEventConsumer {

    private static final String RUPEE = "₹";

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final NotificationService notifications;

    public NotificationEventConsumer(EventCodec codec, ProcessedEvents processed, NotificationService notifications) {
        this.codec = codec;
        this.processed = processed;
        this.notifications = notifications;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS}, groupId = Groups.NOTIFICATIONS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        switch (event.type()) {
            case EventEnvelope.RIDE_STATUS_CHANGED -> {
                RideEventData data = codec.data(event, RideEventData.class);
                processed.runOnce(Groups.NOTIFICATIONS, event.eventId(), () -> rideChanged(data));
            }
            case EventEnvelope.PAYMENT_SUCCEEDED, EventEnvelope.PAYMENT_FAILED -> {
                PaymentEventData data = codec.data(event, PaymentEventData.class);
                processed.runOnce(Groups.NOTIFICATIONS, event.eventId(), () -> paymentChanged(event.type(), data));
            }
            default -> {
                // an event type this consumer does not care about (or a newer one): ignore it
            }
        }
    }

    private void rideChanged(RideEventData e) {
        switch (e.to()) {
            case "DRIVER_ASSIGNED" -> {
                if (e.driverUserId() != null) {
                    notifications.record(e.driverUserId(), NotificationType.RIDE_OFFER, "New ride request",
                            e.pickupAddress() + " to " + e.dropoffAddress() + " · " + money(e.fareTotal()), e.rideId());
                }
            }
            case "DRIVER_ACCEPTED" -> notifications.record(e.riderUserId(), NotificationType.RIDE_ACCEPTED,
                    "Your driver is on the way", "A driver accepted your ride and is heading to your pickup.", e.rideId());
            case "DRIVER_ARRIVED" -> notifications.record(e.riderUserId(), NotificationType.DRIVER_ARRIVED,
                    "Your driver has arrived", "Your driver is waiting at the pickup point.", e.rideId());
            case "RIDE_STARTED" -> notifications.record(e.riderUserId(), NotificationType.TRIP_STARTED,
                    "Your trip has started", "Have a safe ride.", e.rideId());
            case "RIDE_COMPLETED" -> {
                notifications.record(e.riderUserId(), NotificationType.TRIP_COMPLETED,
                        "Trip completed", "You have arrived. Please rate your driver.", e.rideId());
                if (e.driverUserId() != null) {
                    notifications.record(e.driverUserId(), NotificationType.TRIP_COMPLETED,
                            "Trip completed", "Well done. Please rate your rider.", e.rideId());
                }
            }
            case "CANCELLED" -> cancelled(e);
            default -> {
                // REQUESTED / SEARCHING_DRIVER: nobody needs to be told
            }
        }
    }

    private void cancelled(RideEventData e) {
        if ("SYSTEM".equals(e.actor())) {
            notifications.record(e.riderUserId(), NotificationType.NO_DRIVER_FOUND,
                    "No driver available", "We could not find a driver nearby. Please try again.", e.rideId());
        } else if ("DRIVER".equals(e.actor())) {
            notifications.record(e.riderUserId(), NotificationType.RIDE_CANCELLED,
                    "Your driver cancelled", "Your driver cancelled the ride. You can book again.", e.rideId());
        } else if ("RIDER".equals(e.actor()) && e.driverUserId() != null && !"DRIVER_ASSIGNED".equals(e.from())) {
            // While the ride is only an unanswered offer, the offer just disappears. Once a driver accepted, tell them.
            notifications.record(e.driverUserId(), NotificationType.RIDE_CANCELLED,
                    "Ride cancelled", "The rider cancelled this ride.", e.rideId());
        }
    }

    private void paymentChanged(String type, PaymentEventData p) {
        if (EventEnvelope.PAYMENT_SUCCEEDED.equals(type)) {
            notifications.record(p.riderUserId(), NotificationType.PAYMENT_RECEIVED, "Payment received",
                    "We charged " + money(p.amount()) + " for your ride. Thank you!", p.rideId());
            notifications.record(p.driverUserId(), NotificationType.EARNING_CREDITED, "Earning credited",
                    "The rider paid " + money(p.amount()) + " for this trip.", p.rideId());
        } else {
            notifications.record(p.riderUserId(), NotificationType.PAYMENT_FAILED, "Payment failed",
                    "We could not charge " + money(p.amount()) + ": " + p.failureReason() + ". You can try again from the ride.", p.rideId());
        }
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%s%.2f", RUPEE, amount);
    }
}
