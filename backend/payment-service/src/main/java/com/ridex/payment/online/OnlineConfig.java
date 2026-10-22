package com.ridex.payment.online;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the provider: Razorpay when keys are configured, the test provider otherwise. */
@Configuration
public class OnlineConfig {

    private static final Logger log = LoggerFactory.getLogger(OnlineConfig.class);

    @Bean
    CheckoutProvider checkoutProvider(@Value("${ridex.payments.razorpay.key-id:}") String keyId,
                                      @Value("${ridex.payments.razorpay.key-secret:}") String keySecret,
                                      @Value("${ridex.payments.razorpay.webhook-secret:}") String webhookSecret,
                                      @Value("${ridex.payments.razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            log.warn("No Razorpay keys configured: using the TEST checkout (no real payments). Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET to go live");
            return new MockCheckoutProvider();
        }
        log.info("Razorpay checkout enabled ({} mode)", keyId.startsWith("rzp_live_") ? "LIVE" : "test");
        return new RazorpayProvider(baseUrl, keyId, keySecret, webhookSecret);
    }
}
