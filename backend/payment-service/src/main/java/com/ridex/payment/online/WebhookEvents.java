package com.ridex.payment.online;

import java.util.Map;
import java.util.Optional;

import com.ridex.platform.web.BadRequestException;

import tools.jackson.databind.json.JsonMapper;

/** Reading the "payment completed" notice out of a provider's webhook body. Only called AFTER the signature was verified. */
final class WebhookEvents {

    private WebhookEvents() {
    }

    /** Razorpay shape: {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_..","order_id":"order_.."}}}} (also used by the test checkout). */
    static Optional<CheckoutProvider.PaidNotice> razorpay(JsonMapper mapper, String rawBody) {
        try {
            Map<?, ?> body = mapper.readValue(rawBody, Map.class);
            String event = String.valueOf(body.get("event"));
            if (!"payment.captured".equals(event) && !"order.paid".equals(event)) {
                return Optional.empty();   // other events (failed, refunded...) need nothing from us yet
            }
            Map<?, ?> payload = (Map<?, ?>) body.get("payload");
            Map<?, ?> payment = (Map<?, ?>) ((Map<?, ?>) payload.get("payment")).get("entity");
            return Optional.of(new CheckoutProvider.PaidNotice(String.valueOf(payment.get("order_id")), String.valueOf(payment.get("id"))));
        } catch (RuntimeException e) {
            throw new BadRequestException("The webhook could not be read");   // never include the body: it holds payment details
        }
    }

    /** Stripe shape: {"type":"checkout.session.completed","data":{"object":{"id":"cs_..","payment_status":"paid","payment_intent":"pi_.."}}}. */
    static Optional<CheckoutProvider.PaidNotice> stripe(JsonMapper mapper, String rawBody) {
        try {
            Map<?, ?> body = mapper.readValue(rawBody, Map.class);
            String type = String.valueOf(body.get("type"));
            if (!"checkout.session.completed".equals(type) && !"checkout.session.async_payment_succeeded".equals(type)) {
                return Optional.empty();
            }
            Map<?, ?> session = (Map<?, ?>) ((Map<?, ?>) body.get("data")).get("object");
            if (!"paid".equals(session.get("payment_status"))) {
                return Optional.empty();   // completed but not paid yet (a delayed method): wait for the "async_payment_succeeded" event
            }
            Object intent = session.get("payment_intent");
            String id = String.valueOf(session.get("id"));
            return Optional.of(new CheckoutProvider.PaidNotice(id, intent == null ? id : String.valueOf(intent)));
        } catch (RuntimeException e) {
            throw new BadRequestException("The webhook could not be read");
        }
    }
}
