package com.ridex.ridex_app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.ridex.ridex_app.pricing.PricingProperties;

@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingConfig {
}
