package com.project.souklab.security;

import com.project.souklab.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationFailureHandlerTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.OAuth oauth;

    @Mock
    private AppProperties.OAuth.Google google;

    private OAuth2AuthenticationFailureHandler failureHandler;

    @BeforeEach
    void setUp() {
        failureHandler = new OAuth2AuthenticationFailureHandler(appProperties);
        when(appProperties.getOauth()).thenReturn(oauth);
        when(oauth.getGoogle()).thenReturn(google);
        when(google.getAuthorizedRedirectUri()).thenReturn("https://souklab.dz/auth/google/callback");
    }

    @Test
    @DisplayName("onAuthenticationFailure: redirects to frontend callback with error code from OAuth2Error")
    void onAuthenticationFailure_withOAuth2Exception_redirectsToFrontendWithError() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2Error error = new OAuth2Error("access_denied", "User cancelled consent", null);
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://souklab.dz/auth/google/callback?error=access_denied");
    }

    @Test
    @DisplayName("onAuthenticationFailure: extracts error param from request query if present")
    void onAuthenticationFailure_withRequestErrorParam_redirectsWithCode() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("error", "interaction_required");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("unknown"));

        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://souklab.dz/auth/google/callback?error=interaction_required");
    }

    @Test
    @DisplayName("onAuthenticationFailure: adapts localhost authorized URI to forwarded LAN IP")
    void onAuthenticationFailure_withForwardedHost_adaptsRedirectUri() throws IOException {
        when(google.getAuthorizedRedirectUri()).thenReturn("http://localhost/connexion/google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "192.168.100.47");
        request.addHeader("X-Forwarded-Proto", "http");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://192.168.100.47/connexion/google?error=access_denied");
    }

    @Test
    @DisplayName("onAuthenticationFailure: adapts localhost authorized URI to forwarded ngrok https domain")
    void onAuthenticationFailure_withNgrokForwardedHost_adaptsRedirectUri() throws IOException {
        when(google.getAuthorizedRedirectUri()).thenReturn("http://localhost/connexion/google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "souklab.ngrok-free.app");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://souklab.ngrok-free.app/connexion/google?error=access_denied");
    }

    @Test
    @DisplayName("onAuthenticationFailure: adapts localhost to custom reverse proxy port (e.g. :8088)")
    void onAuthenticationFailure_withCustomProxyPort_adaptsRedirectUri() throws IOException {
        when(google.getAuthorizedRedirectUri()).thenReturn("http://localhost/connexion/google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "localhost:8088");
        request.addHeader("X-Forwarded-Proto", "http");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8088/connexion/google?error=access_denied");
    }

    @Test
    @DisplayName("onAuthenticationFailure: ignores backend port 8080 and 0.0.0.0 bind address")
    void onAuthenticationFailure_withBackendPortOrBindAddress_keepsDefaultConfigured() throws IOException {
        when(google.getAuthorizedRedirectUri()).thenReturn("http://localhost/connexion/google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?error=access_denied");

        MockHttpServletRequest reqBind = new MockHttpServletRequest();
        reqBind.addHeader("X-Forwarded-Host", "0.0.0.0:3000");
        MockHttpServletResponse respBind = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(reqBind, respBind, exception);
        assertThat(respBind.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?error=access_denied");
    }

    @Test
    @DisplayName("onAuthenticationFailure: adapts via request serverName and scheme when ForwardedHeaderFilter consumed headers")
    void onAuthenticationFailure_withForwardedHeaderFilterWrappedRequest_adaptsRedirectUri() throws IOException {
        when(google.getAuthorizedRedirectUri()).thenReturn("http://localhost/connexion/google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("192.168.100.47");
        request.setServerPort(80);
        request.setScheme("http");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://192.168.100.47/connexion/google?error=access_denied");

        MockHttpServletRequest ngrokReq = new MockHttpServletRequest();
        ngrokReq.setServerName("souklab.ngrok-free.app");
        ngrokReq.setServerPort(443);
        ngrokReq.setScheme("https");
        MockHttpServletResponse ngrokResp = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(ngrokReq, ngrokResp, exception);
        assertThat(ngrokResp.getRedirectedUrl()).isEqualTo("https://souklab.ngrok-free.app/connexion/google?error=access_denied");

        MockHttpServletRequest ngrokPort80Req = new MockHttpServletRequest();
        ngrokPort80Req.setServerName("souklab.ngrok-free.app");
        ngrokPort80Req.setServerPort(80);
        ngrokPort80Req.setScheme("https");
        MockHttpServletResponse ngrokPort80Resp = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(ngrokPort80Req, ngrokPort80Resp, exception);
        assertThat(ngrokPort80Resp.getRedirectedUrl()).isEqualTo("https://souklab.ngrok-free.app/connexion/google?error=access_denied");
    }
}
