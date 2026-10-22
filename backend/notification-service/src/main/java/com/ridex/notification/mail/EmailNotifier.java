package com.ridex.notification.mail;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.ComplaintEventData;
import com.ridex.platform.contracts.DriverReviewedEventData;
import com.ridex.platform.contracts.PaymentEventData;

/**
 * Turns the important events into email: receipts, payment problems, refunds, complaint outcomes and driver approval. Everything else stays
 * in-app only, because email is a scarce free resource (a free SMTP account allows a few hundred a day) and a person does not want a mail for
 * every status change.
 *
 * Email is BEST EFFORT and never throws: the in-app notification is the record, the email is a courtesy. A failure is logged without any
 * address or content. Messages are plain text and contain no card or bank data (there is none to show).
 */
@Component
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final JavaMailSender mail;
    private final UserDirectory directory;
    private final String from;
    private final String webBase;
    private final boolean enabled;

    public EmailNotifier(JavaMailSender mail, UserDirectory directory, @Value("${ridex.mail.from:no-reply@ridex.local}") String from,
                         @Value("${ridex.frontend.base-url:http://localhost:5173}") String webBase, @Value("${ridex.mail.enabled:true}") boolean enabled) {
        this.mail = mail;
        this.directory = directory;
        this.from = from;
        this.webBase = webBase;
        this.enabled = enabled;
    }

    private static String rupees(BigDecimal value) {
        return value == null ? "" : "₹" + value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public void paymentSucceeded(PaymentEventData p) {
        BigDecimal paid = p.amount() == null ? null : p.amount().subtract(p.discount() == null ? BigDecimal.ZERO : p.discount());
        send(p.riderUserId(), "Your RideX receipt",
                "Thanks for riding with RideX.\n\nReceipt RX-" + p.rideId().toString().substring(0, 8).toUpperCase() + "\nPaid: " + rupees(paid)
                        + (p.method() == null ? "" : " (" + p.method().toLowerCase() + ")")
                        + (p.discount() != null && p.discount().signum() > 0 ? "\nOffer discount: " + rupees(p.discount()) : "")
                        + "\n\nSee or print the full receipt: " + webBase + "/receipt/" + p.rideId() + "\n");
    }

    public void paymentFailed(PaymentEventData p) {
        send(p.riderUserId(), "Your RideX ride needs a payment",
                "We could not take the payment for your last ride.\n\nReason: " + (p.failureReason() == null ? "not enough money in your wallet" : p.failureReason())
                        + "\n\nOpen the ride and press Pay now: " + webBase + "/rides/" + p.rideId() + "\n");
    }

    public void refunded(PaymentEventData p) {
        send(p.riderUserId(), "Your RideX refund", "We refunded " + rupees(p.refundAmount()) + " to your RideX wallet for a ride.\n\nRide: " + webBase + "/rides/" + p.rideId() + "\n");
    }

    public void complaintResolved(ComplaintEventData c) {
        send(c.userId(), "Your RideX complaint was resolved",
                "We looked into your complaint and resolved it.\n"
                        + (c.resolutionNote() == null ? "" : "\nOur answer: " + c.resolutionNote() + "\n")
                        + (c.refundAmount() != null && c.refundAmount().signum() > 0 ? "\nRefund: " + rupees(c.refundAmount()) + " to your wallet.\n" : "")
                        + "\nRide: " + webBase + "/rides/" + c.rideId() + "\n");
    }

    public void driverReviewed(DriverReviewedEventData d) {
        boolean approved = DriverReviewedEventData.APPROVED.equals(d.decision());
        send(d.driverUserId(), approved ? "You can drive with RideX" : "Your RideX driver application",
                approved ? "Your application was approved. Add a vehicle, go online and start accepting rides: " + webBase + "/driver\n"
                        : "Your application was not approved this time.\n" + (d.reason() == null ? "" : "\nReason: " + d.reason() + "\n") + "\nYou can update your documents: " + webBase + "/driver/documents\n");
    }

    private void send(UUID userId, String subject, String text) {
        if (!enabled) {
            return;
        }
        try {
            directory.find(userId).ifPresent(contact -> {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(contact.email());
                message.setSubject(subject);
                message.setText("Hi " + (contact.fullName() == null ? "there" : contact.fullName().split("\\s+")[0]) + ",\n\n" + text + "\nRideX");
                mail.send(message);
            });
        } catch (RuntimeException e) {
            log.warn("An email could not be sent: {}", e.getClass().getSimpleName());   // no address, no content
        }
    }
}
