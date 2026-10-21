package com.ridex.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The payment service: charges riders for completed rides through a remote payment gateway, and publishes the result.
 * It owns the tables "payments" and "mock_gateway_charges" in its own database and exposes /api/payments.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
