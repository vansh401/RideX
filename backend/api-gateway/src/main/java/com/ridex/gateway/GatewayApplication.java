package com.ridex.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The API gateway: one front door for every service.
 *
 * What belongs here is what is the SAME for every service and best done once, at the edge: routing, "is there a valid token at
 * all?", coarse rate limiting, CORS, request logging and correlation ids. What does NOT belong here is anything that needs
 * business knowledge ("may this rider see this ride?"): that stays in the service that owns the data.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
