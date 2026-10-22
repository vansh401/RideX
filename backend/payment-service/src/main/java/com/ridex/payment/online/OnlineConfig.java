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
    CheckoutProvider checkoutProvider(@Value("${ridex.payments.stripe.secret-key:}") String stripeSecret,
                                      @Value("${ridex.payments.stripe.publishable-key:}") String stripePublishable,
                                      @Value("${ridex.payments.stripe.webhook-secret:}") String stripeWebhookSecret,
                                      @Value("${ridex.payments.stripe.base-url:https://api.stripe.com}") String stripeBaseUrl,
                                      @Value("${ridex.frontend.base-url:http://localhost:5173}") String frontendBase,
                                      @Value("${ridex.payments.razorpay.key-id:}") String keyId,
                                      @Value("${ridex.payments.razorpay.key-secret:}") String keySecret,
                                      @Value("${ridex.payments.razorpay.webhook-secret:}") String webhookSecret,
                                      @Value("${ridex.payments.razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        if (!stripeSecret.isBlank()) {
            String mode = stripeSecret.startsWith("sk_live_") || stripeSecret.startsWith("rk_live_") ? "LIVE" : "test";   // only the MODE is logged, never a key
            log.info("Stripe checkout enabled ({} mode)", mode);
            return new StripeProvider(stripeBaseUrl, stripeSecret, stripePublishable, stripeWebhookSecret, frontendBase);
        }
        if (keyId.isBlank() || keySecret.isBlank()) {
            log.warn("No payment keys configured: using the TEST checkout (no real payments). Set STRIPE_SECRET_KEY (or the Razorpay keys) to go live");
            return new MockCheckoutProvider();
        }
        log.info("Razorpay checkout enabled ({} mode)", keyId.startsWith("rzp_live_") ? "LIVE" : "test");
        return new RazorpayProvider(baseUrl, keyId, keySecret, webhookSecret);
    }
}
