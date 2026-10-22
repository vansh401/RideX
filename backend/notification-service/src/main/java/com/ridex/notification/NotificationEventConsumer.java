package com.ridex.notification;

import java.math.BigDecimal;
import java.util.Locale;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.DriverReviewedEventData;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.RideEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.ProcessedEvents;

/**
 * A PURE EVENT CONSUMER: this service never calls another service. It listens to facts ("the ride was accepted", "the
 * payment failed", "the driver was approved"), and turns them into notifications for the right people.
 *
 * Because it only listens:
 * - the services that produce the events know nothing about it, and can be deployed without it;
 * - if this service is down for an hour, the events wait in Kafka and it catches up when it returns;
 * - a duplicate delivery changes nothing: each notification is saved in the same transaction as the "already handled"
 *   marker.
 */
@Component
public class NotificationEventConsumer {

    public static final String GROUP = "notifications";

    private static final String RUPEE = "₹";

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final NotificationService notifications;
    private final com.ridex.notification.mail.EmailNotifier emails;

    public NotificationEventConsumer(EventCodec codec, ProcessedEvents processed, NotificationService notifications,
                                     com.ridex.notification.mail.EmailNotifier emails) {
        this.codec = codec;
        this.processed = processed;
        this.notifications = notifications;
        this.emails = emails;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.DRIVER_EVENTS, Topics.COMPLAINT_EVENTS}, groupId = GROUP)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        switch (event.type()) {
            case EventEnvelope.RIDE_STATUS_CHANGED -> {
                RideEventData data = codec.data(event, RideEventData.class);
                processed.runOnce(GROUP, event.eventId(), () -> rideChanged(data));
            }
            case EventEnvelope.PAYMENT_SUCCEEDED, EventEnvelope.PAYMENT_FAILED -> {
                PaymentEventData data = codec.data(event, PaymentEventData.class);
                // The email goes out AFTER the notification is saved and committed, and only the first time this event is seen
                if (processed.runOnce(GROUP, event.eventId(), () -> paymentChanged(event.type(), data))) {
                    if (EventEnvelope.PAYMENT_SUCCEEDED.equals(event.type())) {
                        emails.paymentSucceeded(data);
                    } else {
                        emails.paymentFailed(data);
                    }
                }
            }
            case EventEnvelope.PAYMENT_REFUNDED -> {
                PaymentEventData data = codec.data(event, PaymentEventData.class);
                if (processed.runOnce(GROUP, event.eventId(), () -> { })) {
                    emails.refunded(data);   // refunds have no in-app type yet: the email is the message
                }
            }
            case EventEnvelope.COMPLAINT_RESOLVED -> {
                com.ridex.platform.contracts.ComplaintEventData data = codec.data(event, com.ridex.platform.contracts.ComplaintEventData.class);
                if (processed.runOnce(GROUP, event.eventId(), () -> { })) {
                    emails.complaintResolved(data);
                }
            }
            case EventEnvelope.DRIVER_APPLICATION_REVIEWED -> {
                DriverReviewedEventData data = codec.data(event, DriverReviewedEventData.class);
                if (processed.runOnce(GROUP, event.eventId(), () -> driverReviewed(data))) {
                    emails.driverReviewed(data);
                }
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

    private void driverReviewed(DriverReviewedEventData d) {
        if (DriverReviewedEventData.APPROVED.equals(d.decision())) {
            notifications.record(d.driverUserId(), NotificationType.DRIVER_APPROVED, "You are approved to drive",
                    "Add an active vehicle and go online to start receiving rides.", null);
        } else {
            notifications.record(d.driverUserId(), NotificationType.DRIVER_REJECTED, "Your application was not accepted",
                    d.reason() == null ? "Please check your details and apply again." : d.reason(), null);
        }
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%s%.2f", RUPEE, amount);
    }
}
