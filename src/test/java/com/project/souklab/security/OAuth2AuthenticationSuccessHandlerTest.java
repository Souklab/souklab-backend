package com.project.souklab.security;

import com.project.souklab.config.OAuthProperties;
import com.project.souklab.dto.auth.JwtResponseDTO;
import com.project.souklab.exception.AppException;
import com.project.souklab.service.auth.AuthService;
import com.project.souklab.util.ServletResponseUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private AuthService authService;

    @Mock
    private ServletResponseUtil servletResponseUtil;

    @Mock
    private AuthorizationCodeStore authorizationCodeStore;

    private OAuthProperties oAuthProperties;
    private OAuth2AuthenticationSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        oAuthProperties = new OAuthProperties();
        oAuthProperties.getGoogle().setAuthorizedRedirectUri("http://localhost/connexion/google");
        successHandler = new OAuth2AuthenticationSuccessHandler(authService, servletResponseUtil, authorizationCodeStore, oAuthProperties);
    }

    @Test
    @DisplayName("onAuthenticationSuccess: redirects to configured callback with authorization code")
    void onAuthenticationSuccess_withValidPrincipal_redirectsWithCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-auth-code-123");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?code=test-auth-code-123");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: falls back to default localhost redirect URI if configured URI is null")
    void onAuthenticationSuccess_withNullAuthorizedUri_fallsBackToDefault() throws Exception {
        oAuthProperties.getGoogle().setAuthorizedRedirectUri(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-fallback-code");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?code=test-fallback-code");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: redirects with error query param when AppException occurs")
    void onAuthenticationSuccess_withAppException_redirectsWithError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        AppException ex = new AppException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING", "Account pending approval");
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenThrow(ex);

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?error=ACCOUNT_PENDING");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: throws IllegalArgumentException if principal is not OAuth2User")
    void onAuthenticationSuccess_withNonOAuth2Principal_throwsException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("regularStringPrincipal");

        assertThatThrownBy(() -> successHandler.onAuthenticationSuccess(request, response, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected principal of type OAuth2User");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: adapts localhost authorized URI to forwarded LAN IP")
    void onAuthenticationSuccess_withForwardedHost_adaptsRedirectUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "192.168.100.47");
        request.addHeader("X-Forwarded-Proto", "http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-auth-code-456");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://192.168.100.47/connexion/google?code=test-auth-code-456");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: adapts localhost authorized URI to forwarded ngrok https domain")
    void onAuthenticationSuccess_withNgrokForwardedHost_adaptsRedirectUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "souklab.ngrok-free.app");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-auth-code-789");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("https://souklab.ngrok-free.app/connexion/google?code=test-auth-code-789");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: adapts localhost to custom reverse proxy port (e.g. :8088)")
    void onAuthenticationSuccess_withCustomProxyPort_adaptsRedirectUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "localhost:8088");
        request.addHeader("X-Forwarded-Proto", "http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-port-code");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8088/connexion/google?code=test-port-code");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: ignores backend port 8080 and 0.0.0.0 bind address")
    void onAuthenticationSuccess_withBackendPortOrBindAddress_keepsDefault() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), eq(request))).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-code-default");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/connexion/google?code=test-code-default");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: adapts via request serverName and scheme when ForwardedHeaderFilter consumed headers")
    void onAuthenticationSuccess_withForwardedHeaderFilterWrappedRequest_adaptsRedirectUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("192.168.100.47");
        request.setServerPort(80);
        request.setScheme("http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = mock(Authentication.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(auth.getPrincipal()).thenReturn(principal);

        JwtResponseDTO jwtResponse = new JwtResponseDTO();
        when(authService.processOAuth2Success(eq(principal), any(), any())).thenReturn(jwtResponse);
        when(authorizationCodeStore.put(jwtResponse)).thenReturn("test-code-filter");

        successHandler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://192.168.100.47/connexion/google?code=test-code-filter");

        MockHttpServletRequest ngrokPort80Req = new MockHttpServletRequest();
        ngrokPort80Req.setServerName("souklab.ngrok-free.app");
        ngrokPort80Req.setServerPort(80);
        ngrokPort80Req.setScheme("https");
        MockHttpServletResponse ngrokPort80Resp = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(ngrokPort80Req, ngrokPort80Resp, auth);
        assertThat(ngrokPort80Resp.getRedirectedUrl()).isEqualTo("https://souklab.ngrok-free.app/connexion/google?code=test-code-filter");
    }
}
