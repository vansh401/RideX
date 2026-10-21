package com.ridex.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/** Every servlet service (including the core) logs the gateway's correlation id. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformRequestIdAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);   // before security, so even rejected requests are traceable
        return registration;
    }

    /**
     * Health checks (every few seconds, from Docker or Kubernetes) and Prometheus scrapes (every 15 s) are not worth a trace or a
     * request metric: they would bury the real requests. Nothing observes calls to /actuator or the core's /api/health.
     */
    @Bean
    ObservationPredicate ignoreActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && (server.getCarrier().getRequestURI().startsWith("/actuator") || server.getCarrier().getRequestURI().equals("/api/health")));
    }

    @Bean
    FilterRegistrationBean<InstanceHeaderFilter> instanceHeaderFilter() {
        FilterRegistrationBean<InstanceHeaderFilter> registration = new FilterRegistrationBean<>(new InstanceHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 4);
        return registration;
    }
}
