package com.ridex.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.ridex.platform.contracts.ComplaintEventData;
import com.ridex.platform.contracts.DriverReviewedEventData;
import com.ridex.platform.contracts.PaymentEventData;

/** What is emailed, to whom, and that an email problem can never break event handling. No mail server: the sender is a mock. */
class EmailNotifierTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final UserDirectory directory = mock(UserDirectory.class);
    private final UUID rider = UUID.randomUUID();
    private final UUID ride = UUID.randomUUID();
    private EmailNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new EmailNotifier(mail, directory, "no-reply@ridex.test", "https://ridex.test", true);
        when(directory.find(rider)).thenReturn(Optional.of(new UserDirectory.Contact("Asha Rao", "asha@example.com")));
    }

    private PaymentEventData payment(String method, String refund, String reason) {
        return new PaymentEventData(UUID.randomUUID(), ride, rider, UUID.randomUUID(), new BigDecimal("200.00"), "INR", "SUCCEEDED", reason, 1, null,
                new BigDecimal("20.00"), method, refund == null ? null : new BigDecimal(refund));
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void aReceiptIsAddressedByFirstNameShowsWhatWasPaidAndLinksToTheReceipt() {
        notifier.paymentSucceeded(payment("WALLET", null, null));

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("asha@example.com");
        assertThat(message.getFrom()).isEqualTo("no-reply@ridex.test");
        assertThat(message.getSubject()).isEqualTo("Your RideX receipt");
        assertThat(message.getText()).startsWith("Hi Asha,").contains("₹180.00").contains("(wallet)").contains("₹20.00").contains("https://ridex.test/receipt/" + ride);
    }

    @Test
    void aFailedPaymentTellsThePersonWhatToDo() {
        notifier.paymentFailed(payment(null, null, "Not enough money in your wallet."));

        assertThat(sent().getText()).contains("Not enough money in your wallet.").contains("Pay now").contains("/rides/" + ride);
    }

    @Test
    void aRefundAndAResolvedComplaintAreEmailedToTheRightPerson() {
        notifier.refunded(payment("WALLET", "75.50", null));
        notifier.complaintResolved(new ComplaintEventData(UUID.randomUUID(), ride, rider, "OVERCHARGED", "RESOLVED", new BigDecimal("40"), "We corrected the fare."));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).getText()).contains("₹75.50");
        assertThat(captor.getAllValues().get(1).getText()).contains("We corrected the fare.").contains("₹40.00");
    }

    @Test
    void aDriverIsToldTheDecisionAndTheReason() {
        UUID driver = UUID.randomUUID();
        when(directory.find(driver)).thenReturn(Optional.of(new UserDirectory.Contact("Dev Patel", "dev@example.com")));

        notifier.driverReviewed(new DriverReviewedEventData(driver, DriverReviewedEventData.REJECTED, "The licence photo is unreadable"));

        assertThat(sent().getText()).contains("not approved").contains("The licence photo is unreadable");
    }

    @Test
    void whenTheAddressIsUnknownNothingIsSent() {
        when(directory.find(rider)).thenReturn(Optional.empty());

        notifier.paymentSucceeded(payment("WALLET", null, null));

        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aBrokenMailServerNeverBreaksTheCaller() {
        doThrow(new MailSendException("smtp is down")).when(mail).send(any(SimpleMailMessage.class));

        assertThatCode(() -> notifier.paymentSucceeded(payment("WALLET", null, null))).doesNotThrowAnyException();
    }

    @Test
    void emailCanBeSwitchedOff() {
        new EmailNotifier(mail, directory, "no-reply@ridex.test", "https://ridex.test", false).paymentSucceeded(payment("WALLET", null, null));

        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
