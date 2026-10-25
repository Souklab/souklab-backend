package com.project.souklab.security;

import com.project.souklab.analytics.AnalyticsMetric;
import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.config.RateLimitEndpointProperties;
import com.project.souklab.config.RateLimitRule;
import com.project.souklab.config.OperationalMetrics;
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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ServletResponseUtil servletResponseUtil;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimitBucketStore bucketStore;
    private RateLimitEndpointProperties endpointProperties;
    private OperationalMetrics metrics = OperationalMetrics.noop();

    public RateLimitFilter(ServletResponseUtil servletResponseUtil, RateLimitProperties rateLimitProperties) {
        this(servletResponseUtil, rateLimitProperties, RateLimitBucketStore.inMemory());
    }

    @Autowired
    public RateLimitFilter(ServletResponseUtil servletResponseUtil, RateLimitProperties rateLimitProperties,
                           RateLimitBucketStore bucketStore) {
        this.servletResponseUtil = servletResponseUtil;
        this.rateLimitProperties = rateLimitProperties;
        this.bucketStore = bucketStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rateLimitProperties == null || !rateLimitProperties.isEnabled();
    }

    public Bucket resolveBucket(String ip) {
        if (rateLimitProperties == null) throw new IllegalStateException("Rate-limit configuration is unavailable");
        return bucketStore.resolve("api:" + ip, rateLimitProperties.getCapacity(), rateLimitProperties.getRefillDuration());
    }

    @Autowired(required = false)
    void setEndpointProperties(RateLimitEndpointProperties endpointProperties) { this.endpointProperties = endpointProperties; }

    @Autowired(required = false)
    void setOperationalMetrics(OperationalMetrics metrics) { this.metrics = metrics; }

    Bucket resolveBucket(HttpServletRequest request) {
        RateLimitRule rule = selectRule(request);
        if (rule == null || !rule.isEnabled()) return resolveBucket(request.getRemoteAddr());
        return bucketStore.resolve("api:" + ruleName(request).value() + ":" + request.getRemoteAddr(),
                rule.getCapacity(), rule.getRefillDuration());
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (endpointDisabled(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe;
        try {
            Bucket bucket = resolveBucket(request);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException unavailable) {
            metrics.recordRateLimitRejection(ruleName(request));
            servletResponseUtil.writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    ApiResponse.error(ApiErrorCode.TOO_MANY_REQUESTS, "Too many requests. Please try again later."));
            return;
        }

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
        } else {
            metrics.recordRateLimitRejection(ruleName(request));
            long secondsToWait = Math.max(1L, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(secondsToWait));
            servletResponseUtil.writeResponse(
                    response,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    ApiResponse.error(ApiErrorCode.TOO_MANY_REQUESTS, "Too many requests. Please try again later.")
            );
        }
    }

    private boolean endpointDisabled(HttpServletRequest request) {
        RateLimitRule rule = selectRule(request);
        return rule != null && !rule.isEnabled();
    }
}
