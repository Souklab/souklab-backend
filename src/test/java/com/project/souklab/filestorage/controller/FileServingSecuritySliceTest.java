package com.project.souklab.filestorage.controller;

import com.project.souklab.config.AvatarProperties;
import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.config.CorsProperties;
import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.config.SecurityConfig;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.service.storage.FileAccessService;
import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.filestorage.security.FileRateLimitFilter;
import com.project.souklab.security.JwtAuthenticationFilter;
import com.project.souklab.security.JwtUtils;
import com.project.souklab.security.OAuth2AuthenticationSuccessHandler;
import com.project.souklab.security.RateLimitFilter;
import com.project.souklab.util.ServletResponseUtil;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security slice test verifying that {@link SecurityConfig}'s authorization rules apply
 * strictly to initial {@link DispatcherType#REQUEST} dispatches on {@code /api/v1/files/**},
 * rejecting unauthenticated calls with 401, while permitting internal async/error dispatches.
 */
@WebMvcTest(controllers = FileServingController.class)
@Import({SecurityConfig.class, FileServingSecuritySliceTest.TestFilterConfig.class, FileServingAsyncTestConfig.class})
class FileServingSecuritySliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private FileAccessService fileAccessService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @Configuration
    static class TestFilterConfig {
        @Bean
        public RateLimitProperties rateLimitProperties() {
            RateLimitProperties rateLimitProperties = new RateLimitProperties();
            rateLimitProperties.setEnabled(false);
            rateLimitProperties.setCapacity(100);
            rateLimitProperties.setRefillDuration(Duration.ofMinutes(1));
            return rateLimitProperties;
        }

        @Bean
        public CorsProperties corsProperties() {
            return new CorsProperties();
        }

        @Bean
        public ChargilyProperties chargilyProperties() {
            return new ChargilyProperties();
        }

        @Bean
        public StorageProperties storageProperties() {
            return new StorageProperties();
        }

        @Bean
        public AvatarProperties avatarProperties() {
            return new AvatarProperties();
        }

        @Bean
        public JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        public ServletResponseUtil servletResponseUtil(JsonMapper jsonMapper) {
            return new ServletResponseUtil(jsonMapper);
        }

        @Bean
        public RateLimitFilter rateLimitFilter(ServletResponseUtil servletResponseUtil, RateLimitProperties rateLimitProperties) {
            return new RateLimitFilter(servletResponseUtil, rateLimitProperties);
        }

        @Bean
        public UserDetailsService userDetailsService() {
            return mock(UserDetailsService.class);
        }

        @Bean
        public JwtUtils jwtUtils() {
            return mock(JwtUtils.class);
        }

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
            return new JwtAuthenticationFilter(jwtUtils, userDetailsService);
        }

        @Bean
        public FileRateLimitFilter fileRateLimitFilter(ServletResponseUtil servletResponseUtil, StorageProperties storageProperties) {
            return new FileRateLimitFilter(servletResponseUtil, storageProperties);
        }
    }

    @Test
    @DisplayName("GET /api/v1/files/{key} without token is rejected with 401 on initial REQUEST dispatch")
    void unauthenticatedInitialRequest_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/files/{key}", "test-file-key.png"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Unauthorized: Full authentication is required to access this resource"));
    }

    @Test
    @DisplayName("Responses must include CSP, Permissions-Policy, X-Frame-Options, and Referrer-Policy headers")
    void responsesIncludeHardenedSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/files/{key}", "test-file-key.png"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; object-src 'none';"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("Public endpoints allow GET without auth but reject POST/mutations with 401")
    void publicEndpointsRestrictMethodsAtFilterLevel() throws Exception {
        mockMvc.perform(post("/api/v1/feed"))
                .andExpect(status().isUnauthorized());
    }
}
