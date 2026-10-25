package com.project.souklab.security;

import com.project.souklab.config.AvatarProperties;
import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.filestorage.security.FileRateLimitFilter;
import com.project.souklab.util.ServletResponseUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFailClosedTest {

    private final ServletResponseUtil responses = new ServletResponseUtil(new JsonMapper());
    private final RateLimitBucketStore unavailable = (key, capacity, refill) -> {
        throw new IllegalStateException("redis unavailable");
    };

    @Test
    void globalLimiterReturns429WhenSharedStoreIsUnavailable() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setCapacity(1);
        properties.setRefillDuration(Duration.ofMinutes(1));
        RateLimitFilter filter = new RateLimitFilter(responses, properties, unavailable);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/v1/test"), response, chain);

        assertRejected(response, chain);
    }

    @Test
    void avatarLimiterReturns429WhenSharedStoreIsUnavailable() throws Exception {
        AvatarProperties properties = new AvatarProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setCapacity(1);
        properties.getRateLimit().setRefillDuration(Duration.ofMinutes(1));
        AvatarUploadRateLimitFilter filter = new AvatarUploadRateLimitFilter(responses, properties, unavailable);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("POST", "/api/v1/users/me/avatars"), response, chain);

        assertRejected(response, chain);
    }

    @Test
    void fileLimiterReturns429WhenSharedStoreIsUnavailable() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setCapacity(1);
        properties.getRateLimit().setRefillDuration(Duration.ofMinutes(1));
        FileRateLimitFilter filter = new FileRateLimitFilter(responses, properties, unavailable);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/v1/files/test.pdf"), response, chain);

        assertRejected(response, chain);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private static void assertRejected(MockHttpServletResponse response, MockFilterChain chain) throws Exception {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many requests");
        assertThat(chain.getRequest()).isNull();
    }
}
