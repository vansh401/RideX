package com.ridex.ridex_app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.ridex.ridex_app.ride.MatchingProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfig {
}
