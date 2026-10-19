package com.ridex.ridex_app.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "no-reply@ridex.local");

    @Test
    void sendsTheVerificationLinkToTheUser() {
        sender.sendVerificationEmail("asha@example.com", "Asha Rao",
                "http://localhost:5173/verify-email?token=abc", "123456");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@ridex.local");
        assertThat(message.getTo()).containsExactly("asha@example.com");
        assertThat(message.getSubject()).isEqualTo("Verify your RideX account");
        assertThat(message.getText())
                .contains("Asha Rao")
                .contains("http://localhost:5173/verify-email?token=abc")
                .contains("123456");
    }

    @Test
    void sendsThePasswordResetLinkToTheUser() {
        sender.sendPasswordResetEmail("asha@example.com", "Asha Rao",
                "http://localhost:5173/reset-password?token=xyz");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertThat(message.getTo()).containsExactly("asha@example.com");
        assertThat(message.getSubject()).isEqualTo("Reset your RideX password");
        assertThat(message.getText()).contains("http://localhost:5173/reset-password?token=xyz");
    }
}
