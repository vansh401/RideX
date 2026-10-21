package com.ridex.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Prices a trip. Stateless: no database, no Kafka. Give it two points and a category, get a fare breakdown back. */
@SpringBootApplication
@EnableConfigurationProperties(PricingProperties.class)
public class PricingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
