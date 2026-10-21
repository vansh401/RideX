package com.ridex.payment;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Comes back for payments that were left PENDING because the gateway did not answer. Each one is retried with the SAME
 * idempotency key, so if the first call actually went through (and only the answer was lost), the gateway simply returns
 * the original result instead of charging again.
 */
@Component
public class PendingPaymentRetrier {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRetrier.class);

    private final PaymentRepository repository;
    private final PaymentService service;

    public PendingPaymentRetrier(PaymentRepository repository, PaymentService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ridex.payments.retry-interval-ms:3000}")
    public void retryDuePayments() {
        for (UUID id : repository.findDueIds(Instant.now())) {
            try {
                service.retryPending(id);
            } catch (RuntimeException e) {
                // One payment failing (or being changed by someone else meanwhile) must not stop the others
                log.warn("Retrying payment {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
    }
}
