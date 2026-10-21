package com.ridex.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

@Configuration
public class ObservationConfig {

    /**
     * Health checks (every few seconds, from Docker or Kubernetes) and Prometheus scrapes (every 15 s) are not worth a trace or a
     * request metric: they would bury the real requests. Nothing observes calls to /actuator.
     */
    @Bean
    ObservationPredicate ignoreActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getPath().value().startsWith("/actuator"));
    }
}
