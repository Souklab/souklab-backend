package com.project.souklab.security;

import com.project.souklab.dto.auth.JwtResponseDTO;
import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.exception.AppException;
import com.project.souklab.config.AppProperties;
import com.project.souklab.service.auth.AuthService;
import com.project.souklab.util.ServletResponseUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Lazy
    private final AuthService authService;
    private final ServletResponseUtil servletResponseUtil;
    private final AuthorizationCodeStore authorizationCodeStore;
    private final AppProperties appProperties;

    public OAuth2AuthenticationSuccessHandler(AuthService authService, ServletResponseUtil servletResponseUtil) {
        this(authService, servletResponseUtil, null, null);
    }

    public OAuth2AuthenticationSuccessHandler(AuthService authService, ServletResponseUtil servletResponseUtil,
                                               AuthorizationCodeStore authorizationCodeStore) {
        this(authService, servletResponseUtil, authorizationCodeStore, null);
    }

    @Autowired
    public OAuth2AuthenticationSuccessHandler(AuthService authService, ServletResponseUtil servletResponseUtil,
                                               AuthorizationCodeStore authorizationCodeStore,
                                               AppProperties appProperties) {
        this.authService = authService;
        this.servletResponseUtil = servletResponseUtil;
        this.authorizationCodeStore = authorizationCodeStore;
        this.appProperties = appProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OAuth2User oAuth2User)) {
            throw new IllegalArgumentException("Expected principal of type OAuth2User, but found: "
                    + (authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getSimpleName() : "null"));
        }

        String intentRole = extractIntentRole(request);

        JwtResponseDTO jwtResponse;
        try {
            jwtResponse = authService.processOAuth2Success(oAuth2User, intentRole, request);
        } catch (AppException exception) {
            log.warn("OAuth2 process refused/failed: {}", exception.getMessage());
            clearIntentCookie(request, response);
            String errorCode = exception.getErrorCode() != null ? exception.getErrorCode() : "AUTH_FAILED";
            String callback = resolveAuthorizedRedirectUri(request);
            response.sendRedirect(UriComponentsBuilder.fromUriString(callback)
                    .queryParam("error", errorCode).build().toUriString());
            return;
        } catch (Exception exception) {
            log.error("OAuth2 authentication unexpected error", exception);
            clearIntentCookie(request, response);
            String callback = resolveAuthorizedRedirectUri(request);
            response.sendRedirect(UriComponentsBuilder.fromUriString(callback)
                    .queryParam("error", "AUTH_FAILED").build().toUriString());
            return;
        }

        clearIntentCookie(request, response);

        if (authorizationCodeStore == null) {
            ApiResponse<JwtResponseDTO> apiResponse = ApiResponse.success(jwtResponse,
                    "Google OAuth authentication successful.");
            servletResponseUtil.writeResponse(response, HttpServletResponse.SC_OK, apiResponse);
            return;
        }
        String code = authorizationCodeStore.put(jwtResponse);
        String callback = resolveAuthorizedRedirectUri(request);
        response.sendRedirect(UriComponentsBuilder.fromUriString(callback)
                .queryParam("code", code).build().toUriString());
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

    private String extractIntentRole(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (OAuthCookie.Intent.NAME.value().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            String sessionRole = (String) session.getAttribute(OAuthCookie.Intent.NAME.value());
            if (sessionRole != null) {
                session.removeAttribute(OAuthCookie.Intent.NAME.value());
                return sessionRole;
            }
        }
        return null;
    }

    private void clearIntentCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie clearCookie = ResponseCookie.from(OAuthCookie.Intent.NAME.value(), "")
                .path("/")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
    }
}
