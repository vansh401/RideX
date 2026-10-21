package com.ridex.ridex_app.observability;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ridex.platform.web.LogContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every request whose URL has a {rideId} (accept, cancel, timeline, ratings ...) logs that ride id on each line it writes, with no change
 * to the code of those endpoints. (Requesting a ride has no id yet; RideService sets it as soon as the ride exists.)
 */
@Configuration
public class RideLogContextConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (variables instanceof Map<?, ?> map && map.get("rideId") != null) {
                    MDC.put(LogContext.RIDE_ID, String.valueOf(map.get("rideId")));
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                MDC.remove(LogContext.RIDE_ID);   // threads are reused
            }
        });
    }
}
