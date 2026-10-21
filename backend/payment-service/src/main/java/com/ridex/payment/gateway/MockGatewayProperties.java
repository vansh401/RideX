package com.ridex.payment.gateway;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridex.payments.mock")
public record MockGatewayProperties(BigDecimal declineAboveAmount) {
}
