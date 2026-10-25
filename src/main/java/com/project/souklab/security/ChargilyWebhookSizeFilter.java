package com.project.souklab.security;

import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.util.ServletResponseUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects oversized Chargily webhook requests before MVC buffers their raw body. */
@RequiredArgsConstructor
public class ChargilyWebhookSizeFilter extends OncePerRequestFilter {
    public static final String WEBHOOK_URI = "/api/v1/integrations/chargily/webhook";

    private final ChargilyProperties chargilyProperties;
    private final ServletResponseUtil servletResponseUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (HttpMethod.POST.matches(request.getMethod()) && WEBHOOK_URI.equals(request.getRequestURI())
                && contentLength(request) > chargilyProperties.getRequestBodyLimit()) {
            servletResponseUtil.writeResponse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    ApiResponse.error(ApiErrorCode.BAD_REQUEST,
                            "Webhook body exceeds the configured request limit"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private long contentLength(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length >= 0) {
            return length;
        }
        String header = request.getHeader("Content-Length");
        if (header == null || header.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
