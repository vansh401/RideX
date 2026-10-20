package com.ridex.ridex_app.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket with STOMP.
 *
 * WebSocket is a long-lived two-way connection between the browser and the server: either side can send
 * at any moment. It replaces polling ("ask again every 4 seconds") with pushing ("tell me when it changes").
 * STOMP is a tiny text protocol on top of it that adds what raw WebSocket lacks: destinations to subscribe
 * to, and frames such as CONNECT, SUBSCRIBE, SEND and MESSAGE.
 *
 * Destinations in RideX:
 *   client sends to   /app/driver/location            (driver location stream)
 *   client listens to /user/queue/ride-updates        (private: my ride changed)
 *                     /user/queue/offers              (private: a ride is offered to me / withdrawn)
 *                     /user/queue/notifications       (private: a new notification)
 *                     /user/queue/errors              (private: a message of mine was refused)
 *                     /topic/rides/{id}/location      (shared: the driver's position on THIS ride)
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfig implements WebSocketMessageBrokerConfigurer {

    private final StompSecurityInterceptor securityInterceptor;
    private final RealtimeProperties properties;

    public RealtimeConfig(StompSecurityInterceptor securityInterceptor, RealtimeProperties properties) {
        this.securityInterceptor = securityInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Only our own pages may open a socket. (Browsers send an Origin header and cannot fake it.)
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // The in-memory broker routes messages to the sessions of THIS server. RedisRealtimeBridge makes
        // every server receive every message, so this is enough however many servers run.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {properties.heartbeatMs(), properties.heartbeatMs()})
                .setTaskScheduler(brokerHeartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(securityInterceptor);
    }

    @Bean
    public ThreadPoolTaskScheduler brokerHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }

    /** Every server listens to the one Redis channel (see RedisRealtimeBridge). */
    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
                                                                   RedisRealtimeBridge bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bridge, new ChannelTopic(RedisRealtimeBridge.CHANNEL));
        return container;
    }
}
