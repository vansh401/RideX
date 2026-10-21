package com.ridex.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the "json" profile every log line is ONE JSON object, and the fields in the logging context (request id, user id, ride id) are
 * fields of that object. This is what a log system (Loki, Elasticsearch, CloudWatch) indexes.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    @Configuration
    static class Empty {
    }

    @Test
    void theJsonProfileWritesOneJsonObjectPerLineWithTheContextAsFields(CapturedOutput output) throws Exception {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("json");
        try (ConfigurableApplicationContext ignored = app.run("--spring.main.banner-mode=off", "--spring.application.name=log-test")) {
            try (LogContext.Scope user = LogContext.with(LogContext.USER_ID, "11111111-2222-3333-4444-555555555555");
                    LogContext.Scope ride = LogContext.ride("rrrrrrrr-0000-0000-0000-000000000001")) {
                MDC.put("requestId", "req-12345678");
                LoggerFactory.getLogger("ridex.test").info("Ride status changed: {} -> {}", "SEARCHING", "DRIVER_OFFERED");
            } finally {
                MDC.remove("requestId");
            }
        }

        String line = Arrays.stream(output.getAll().split("\\R"))
                .filter(l -> l.contains("Ride status changed")).findFirst().orElseThrow();
        JsonNode json = new JsonMapper().readTree(line);
        assertThat(json.path("message").asString()).isEqualTo("Ride status changed: SEARCHING -> DRIVER_OFFERED");
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(json.at("/service/name").asString()).isEqualTo("log-test");
        assertThat(json.path("requestId").asString()).isEqualTo("req-12345678");
        assertThat(json.path("userId").asString()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(json.path("rideId").asString()).isEqualTo("rrrrrrrr-0000-0000-0000-000000000001");
        assertThat(json.has("@timestamp")).isTrue();
    }

    @Test
    void aScopeRestoresWhateverWasInTheContextBefore() {
        MDC.put(LogContext.RIDE_ID, "outer");
        try (LogContext.Scope ignored = LogContext.ride("inner")) {
            assertThat(MDC.get(LogContext.RIDE_ID)).isEqualTo("inner");
        }
        assertThat(MDC.get(LogContext.RIDE_ID)).isEqualTo("outer");
        MDC.remove(LogContext.RIDE_ID);
        try (LogContext.Scope ignored = LogContext.ride("only")) {
            assertThat(MDC.get(LogContext.RIDE_ID)).isEqualTo("only");
        }
        assertThat(MDC.get(LogContext.RIDE_ID)).isNull();
    }

    @Test
    void anEmailAddressCanBeMentionedInALogOnlyInItsMaskedForm() {
        assertThat(LogContext.maskEmail("asha.rao@example.com")).isEqualTo("a***@example.com");
        assertThat(LogContext.maskEmail("nonsense")).isEqualTo("***");
        assertThat(LogContext.maskEmail(null)).isEqualTo("(none)");
    }
}
