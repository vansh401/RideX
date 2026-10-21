package com.ridex.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InstanceHeaderFilterTest {

    @Test
    void everyResponseNamesTheInstanceThatAnsweredIt() throws Exception {
        InstanceHeaderFilter filter = new InstanceHeaderFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader("X-Instance")).isNotBlank().isEqualTo(filter.instance());
    }

    @Test
    void theHeaderIsSetBeforeTheRequestIsHandledSoFailedRequestsCarryItToo() throws Exception {
        InstanceHeaderFilter filter = new InstanceHeaderFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                    throw new IllegalStateException("boom");
                }
            });
        } catch (IllegalStateException expected) {
            // the service failed
        }

        assertThat(response.getHeader("X-Instance")).isNotBlank();
    }
}
