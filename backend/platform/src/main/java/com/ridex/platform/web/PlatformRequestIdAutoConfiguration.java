package com.ridex.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

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

    @Bean
    FilterRegistrationBean<InstanceHeaderFilter> instanceHeaderFilter() {
        FilterRegistrationBean<InstanceHeaderFilter> registration = new FilterRegistrationBean<>(new InstanceHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 4);
        return registration;
    }
}
