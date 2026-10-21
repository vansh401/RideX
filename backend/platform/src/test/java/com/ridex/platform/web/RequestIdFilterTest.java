package com.ridex.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String suppliedId, MockHttpServletResponse response, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (suppliedId != null) {
            request.addHeader("X-Request-Id", suppliedId);
        }
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get("requestId"));
            }
        });
        return seenInChain.get();
    }

    @Test
    void theIdFromTheGatewayIsInTheLogContextWhileTheRequestRunsAndEchoedBack() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run("trace-1234567", response, new AtomicReference<>());

        assertThat(seen).isEqualTo("trace-1234567");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("trace-1234567");
    }

    @Test
    void aRequestWithoutOneGetsAFreshId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(null, response, new AtomicReference<>());

        assertThat(seen).hasSize(36);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen);
    }

    @Test
    void anUnsafeIdIsReplacedBecauseItEndsUpInLogFiles() throws Exception {
        String seen = run("evil\nINJECTED log line", new MockHttpServletResponse(), new AtomicReference<>());

        assertThat(seen).doesNotContain("INJECTED").hasSize(36);
    }

    @Test
    void theIdIsRemovedAfterwardsSoItNeverLeaksIntoTheNextRequestOnTheSameThread() throws Exception {
        run("trace-1234567", new MockHttpServletResponse(), new AtomicReference<>());

        assertThat(MDC.get("requestId")).isNull();
    }
}
