package com.project.souklab.security;

import com.project.souklab.config.RateLimitEndpointProperties;
import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.util.ServletResponseUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setCapacity(2);
        properties.setRefillDuration(Duration.ofMinutes(1));
        filter = new RateLimitFilter(new ServletResponseUtil(new JsonMapper()), properties);
    }

    @Test
    void disabledConfigurationSkipsFiltering() {
        properties.setEnabled(false);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest())).isTrue();
    }

    @Test
    void missingConfigurationSkipsBeforeDereferencingTheRateLimitPolicy() {
        RateLimitFilter nullFilter = new RateLimitFilter(new ServletResponseUtil(new JsonMapper()), null);

        assertThat(nullFilter.shouldNotFilter(new MockHttpServletRequest())).isTrue();
    }

    @Test
    void enabledConfigurationConsumesPerIpTokensAndRejectsAfterCapacity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.setRemoteAddr("198.51.100.1");
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(chain.getRequest()).isNotNull();
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockFilterChain rejectedChain = new MockFilterChain();
        filter.doFilter(request, rejected, rejectedChain);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejectedChain.getRequest()).isNull();
        assertThat(filter.resolveBucket("198.51.100.1")).isSameAs(filter.resolveBucket("198.51.100.1"));
    }

    @Test
    void explicitlyDisabledEndpointClassBypassesGlobalBucket() throws Exception {
        RateLimitEndpointProperties endpointProperties = new RateLimitEndpointProperties();
        endpointProperties.getPublicApi().setEnabled(false);
        filter.setEndpointProperties(endpointProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/catalog");
        request.setRemoteAddr("198.51.100.2");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
