package com.project.souklab.security;

import com.project.souklab.analytics.AnalyticsMetric;
import java.time.Duration;

import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.config.OperationalMetrics;
import com.project.souklab.config.RateLimitEndpointProperties;
import com.project.souklab.config.RateLimitRule;
import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.util.ServletResponseUtil;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Authenticated-user bucket layered after JWT authentication. */
@Component
public class UserRateLimitFilter extends OncePerRequestFilter {
    private final ServletResponseUtil responses;
    private final RateLimitProperties properties;
    private final RateLimitBucketStore store;
    private RateLimitEndpointProperties endpointProperties;
    private OperationalMetrics metrics = OperationalMetrics.noop();

    public UserRateLimitFilter(ServletResponseUtil responses, RateLimitProperties properties, RateLimitBucketStore store) {
        this.responses = responses;
        this.properties = properties;
        this.store = store;
    }

    @Autowired
    public UserRateLimitFilter(ObjectProvider<ServletResponseUtil> responses,
                               RateLimitProperties properties, ObjectProvider<RateLimitBucketStore> store) {
        this(responses.getIfAvailable(), properties,
                store.getIfAvailable(RateLimitBucketStore::inMemory));
    }

    @Autowired(required = false)
    void setOperationalMetrics(OperationalMetrics metrics) { this.metrics = metrics; }

    @Autowired(required = false)
    void setEndpointProperties(RateLimitEndpointProperties endpointProperties) {
        this.endpointProperties = endpointProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return properties == null
                || !properties.isUserEnabled()
                || SecurityContextHolder.getContext().getAuthentication() == null;
    }

    public Bucket resolveBucket(String username) {
        if (properties == null) throw new IllegalStateException("Rate-limit configuration is unavailable");
        return store.resolve("user:" + username, properties.getUserCapacity(), properties.getUserRefillDuration());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) { chain.doFilter(request, response); return; }
        try {
            RateLimitRule rule = selectRule(request);
            if (rule != null && !rule.isEnabled()) {
                chain.doFilter(request, response);
                return;
            }
            RateLimitProperties global = properties;
            int capacity = rule != null && rule.getUserCapacity() > 0
                    ? rule.getUserCapacity() : global.getUserCapacity();
            Duration refill = rule != null && rule.getUserRefillDuration() != null
                    ? rule.getUserRefillDuration() : global.getUserRefillDuration();
            AnalyticsMetric.Key scope = ruleName(request);
            Bucket bucket = store.resolve("user:" + scope.value() + ":" + authentication.getName(), capacity, refill);
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                metrics.recordRateLimitRejection(AnalyticsMetric.Operational.Scope.USER);
                long secondsToWait = Math.max(1L, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(secondsToWait));
                reject(response);
                return;
            }
        } catch (RuntimeException unavailable) {
            metrics.recordRateLimitRejection(AnalyticsMetric.Operational.Scope.USER);
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private RateLimitRule selectRule(HttpServletRequest request) {
        if (endpointProperties == null) return null;
        String path = request.getRequestURI();
        if (path.contains("/integrations/chargily/webhook")) return endpointProperties.getChargilyWebhook();
        if (isAnalyticsJobPath(path)) {
            if (path.endsWith("/download")) return endpointProperties.getCsvExports();
            if (path.endsWith("/result")) return endpointProperties.getAnalyticsResults();
            return endpointProperties.getAnalyticsJobs();
        }
        if (path.startsWith("/api/v1/auth/")) return endpointProperties.getAuthentication();
        if (path.startsWith("/api/v1/admin/")) return endpointProperties.getAdminApi();
        return endpointProperties.getPublicApi();
    }

    private AnalyticsMetric.Key ruleName(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.contains("/download")) return RateLimitScope.Endpoint.Csv.EXPORTS;
        if (isAnalyticsJobPath(path)) return RateLimitScope.Endpoint.Analytics.API;
        if (path.startsWith("/api/v1/auth/")) return RateLimitScope.Endpoint.Authentication.API;
        if (path.startsWith("/api/v1/admin/")) return RateLimitScope.Endpoint.Administration.API;
        return RateLimitScope.Endpoint.Public.API;
    }

    private boolean isAnalyticsJobPath(String path) {
        return path.contains("/analytics/jobs") || path.contains("/stats/jobs")
                || path.contains("/analytics/rollups/") || path.contains("/stats/rollups/");
    }

    private void reject(HttpServletResponse response) throws IOException {
        if (responses != null) {
            responses.writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    ApiResponse.error(ApiErrorCode.TOO_MANY_REQUESTS, "Too many requests. Please try again later."));
        } else {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests. Please try again later.");
        }
    }
}
