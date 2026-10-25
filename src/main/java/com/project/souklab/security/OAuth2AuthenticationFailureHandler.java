package com.project.souklab.security;

import com.project.souklab.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.project.souklab.util.LogSanitizer;
import java.io.IOException;
import java.util.Map;

/**
 * Handles OAuth2 authentication failures (such as user cancelling consent or account refusal)
 * by redirecting to the frontend authorized redirect URI with an ?error=<CODE> query parameter,
 * rather than Spring Security's default /login?error.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final String DEFAULT_ERROR_CODE = "access_denied";

    private static final Map<String, String> KNOWN_ERROR_CODES = Map.ofEntries(
            Map.entry("access_denied", "access_denied"),
            Map.entry("invalid_request", "invalid_request"),
            Map.entry("unauthorized_client", "unauthorized_client"),
            Map.entry("unsupported_response_type", "unsupported_response_type"),
            Map.entry("invalid_scope", "invalid_scope"),
            Map.entry("server_error", "server_error"),
            Map.entry("temporarily_unavailable", "temporarily_unavailable"),
            Map.entry("login_required", "login_required"),
            Map.entry("consent_required", "consent_required"),
            Map.entry("interaction_required", "interaction_required"),
            Map.entry("account_selection_required", "account_selection_required"),
            Map.entry("oauth2_error", "oauth2_error")
    );

    private final AppProperties appProperties;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String errorCode = resolveErrorCode(request, exception);
        log.warn("OAuth2 authentication failure: code={}, message={}", LogSanitizer.sanitize(errorCode),
                LogSanitizer.sanitize(exception != null ? exception.getMessage() : null));

        String callback = resolveAuthorizedRedirectUri(request);
        String targetUrl = UriComponentsBuilder.fromUriString(callback)
                .queryParam("error", errorCode)
                .build()
                .toUriString();

        response.sendRedirect(targetUrl);
    }

    String resolveAuthorizedRedirectUri() {
        return resolveAuthorizedRedirectUri(null);
    }

    String resolveAuthorizedRedirectUri(HttpServletRequest request) {
        String configured = null;
        if (appProperties != null && appProperties.getOauth() != null
                && appProperties.getOauth().getGoogle() != null
                && appProperties.getOauth().getGoogle().getAuthorizedRedirectUri() != null
                && !appProperties.getOauth().getGoogle().getAuthorizedRedirectUri().isBlank()) {
            configured = appProperties.getOauth().getGoogle().getAuthorizedRedirectUri().trim();
        }
        if (configured == null) {
            configured = "http://localhost/connexion/google";
        }

        if (request != null && configured.startsWith("http://localhost")) {
            String forwardedHost = request.getHeader("X-Forwarded-Host");
            String host = null;
            String proto = null;

            if (forwardedHost != null && !forwardedHost.isBlank()) {
                host = forwardedHost.split(",")[0].trim();
                String forwardedProto = request.getHeader("X-Forwarded-Proto");
                proto = (forwardedProto != null && !forwardedProto.isBlank())
                        ? forwardedProto.split(",")[0].trim()
                        : "http";
            } else {
                // When server.forward-headers-strategy=framework is enabled,
                // Spring's ForwardedHeaderFilter consumes X-Forwarded-Host and populates serverName, serverPort, scheme
                String serverName = request.getServerName();
                if (serverName != null && !serverName.isBlank()
                        && !serverName.equalsIgnoreCase("localhost")
                        && !serverName.startsWith("0.0.0.0")
                        && !serverName.equals("backend")
                        && !serverName.equals("app")) {
                    proto = request.getScheme();
                    int serverPort = request.getServerPort();
                    boolean isDefaultPort = (serverPort <= 0)
                            || ("http".equalsIgnoreCase(proto) && serverPort == 80)
                            || ("https".equalsIgnoreCase(proto) && (serverPort == 443 || serverPort == 80));
                    host = isDefaultPort ? serverName : serverName + ":" + serverPort;
                }
            }

            if (host != null && !host.isBlank()
                    && !host.equalsIgnoreCase("localhost")
                    && !host.equalsIgnoreCase("localhost:80")
                    && !host.equalsIgnoreCase("localhost:8080")
                    && !host.startsWith("0.0.0.0")) {
                if (proto == null || proto.isBlank()) {
                    proto = "http";
                }
                if ("https".equalsIgnoreCase(proto) && (host.endsWith(":80") || host.endsWith(":443"))) {
                    host = host.substring(0, host.lastIndexOf(':'));
                } else if ("http".equalsIgnoreCase(proto) && host.endsWith(":80")) {
                    host = host.substring(0, host.lastIndexOf(':'));
                }
                String path = UriComponentsBuilder.fromUriString(configured).build().getPath();
                return proto + "://" + host + (path != null ? path : "/connexion/google");
            }
        }

        return configured;
    }

    private String resolveErrorCode(HttpServletRequest request, AuthenticationException exception) {
        String requestError = request.getParameter("error");
        if (requestError != null) {
            String resolved = KNOWN_ERROR_CODES.get(requestError.trim().toLowerCase());
            if (resolved != null) {
                return resolved;
            }
        }
        if (exception instanceof OAuth2AuthenticationException oauthException) {
            OAuth2Error error = oauthException.getError();
            if (error != null && error.getErrorCode() != null) {
                String resolved = KNOWN_ERROR_CODES.get(error.getErrorCode().trim().toLowerCase());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return DEFAULT_ERROR_CODE;
    }
}
