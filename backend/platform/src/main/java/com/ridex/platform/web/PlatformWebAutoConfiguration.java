package com.ridex.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * The shared JSON error handling, for every servlet service. The core application has its own handler (it predates the
 * platform library), so it switches this one off with ridex.platform.web-errors.enabled=false.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "ridex.platform.web-errors.enabled", havingValue = "true", matchIfMissing = true)
@Import(PlatformExceptionHandler.class)
public class PlatformWebAutoConfiguration {
}
