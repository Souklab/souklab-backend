package com.project.souklab.security;

import jakarta.servlet.http.Cookie;
import java.lang.Long;
import java.lang.String;

import com.project.souklab.config.JwtProperties;
import java.time.Clock;
import com.project.souklab.service.auth.AuthService;
import com.project.souklab.util.ServletResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import com.project.souklab.dto.auth.JwtResponseDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying principal type-safety guards in JwtUtils and OAuth2AuthenticationSuccessHandler.
 * Ensures unexpected principal types (e.g. String or AnonymousAuthenticationToken) result in clear
 * IllegalArgumentException rather than unhandled ClassCastException.
 */
class SecurityPrincipalGuardsTest {

    private JwtUtils jwtUtils;
    private OAuth2AuthenticationSuccessHandler oAuth2Handler;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("secretKeyForTestingGuardsOnlyMin32BytesLength123");
        jwtProperties.setAccessTokenExpirationMs(3600000L);
        jwtProperties.setRefreshTokenExpirationMs(86400000L);

        jwtUtils = new JwtUtils(jwtProperties, Clock.systemUTC());

        AuthService authService = Mockito.mock(AuthService.class);
        ServletResponseUtil servletResponseUtil = Mockito.mock(ServletResponseUtil.class);
        oAuth2Handler = new OAuth2AuthenticationSuccessHandler(authService, servletResponseUtil);
    }

    /**
     * Verifies JwtUtils.generateAccessToken throws IllegalArgumentException when principal is not a UserDetails.
     */
    @Test
    @DisplayName("JwtUtils: generateAccessToken rejects non-UserDetails principal with IllegalArgumentException")
    void testJwtUtils_generateAccessToken_rejectsNonUserDetailsPrincipal() {
        System.out.println("=== JWT ACCESS TOKEN GUARD EVIDENCE ===");
        Authentication anonymousAuth = new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                List.of(SecurityAuthority.Anonymous.ROLE)
        );

        assertThatThrownBy(() -> jwtUtils.generateAccessToken(anonymousAuth))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ClassCastException.class)
                .hasMessageStartingWith("Expected principal of type UserDetails, but found: String")
                .satisfies(ex -> {
                    System.out.println("Exception: " + ex.getClass().getName());
                    System.out.println("Message: " + ex.getMessage());
                });

        Authentication tokenWithCustomPrincipal = new TestingAuthenticationToken(12345L, "creds");
        assertThatThrownBy(() -> jwtUtils.generateAccessToken(tokenWithCustomPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ClassCastException.class)
                .hasMessageStartingWith("Expected principal of type UserDetails, but found: Long");

        System.out.println("Proof: JwtUtils.generateAccessToken guarded against non-UserDetails principal");
    }

    /**
     * Verifies JwtUtils.generateRefreshToken throws IllegalArgumentException when principal is not a UserDetails.
     */
    @Test
    @DisplayName("JwtUtils: generateRefreshToken rejects non-UserDetails principal with IllegalArgumentException")
    void testJwtUtils_generateRefreshToken_rejectsNonUserDetailsPrincipal() {
        System.out.println("=== JWT REFRESH TOKEN GUARD EVIDENCE ===");
        Authentication anonymousAuth = new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                List.of(SecurityAuthority.Anonymous.ROLE)
        );

        assertThatThrownBy(() -> jwtUtils.generateRefreshToken(anonymousAuth))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ClassCastException.class)
                .hasMessageStartingWith("Expected principal of type UserDetails, but found: String")
                .satisfies(ex -> {
                    System.out.println("Exception: " + ex.getClass().getName());
                    System.out.println("Message: " + ex.getMessage());
                });

        System.out.println("Proof: JwtUtils.generateRefreshToken guarded against non-UserDetails principal");
    }

    /**
     * Verifies OAuth2AuthenticationSuccessHandler throws IllegalArgumentException when principal is not an OAuth2User.
     */
    @Test
    @DisplayName("OAuth2AuthenticationSuccessHandler: rejects non-OAuth2User principal with IllegalArgumentException")
    void testOAuth2Handler_rejectsNonOAuth2UserPrincipal() {
        System.out.println("=== OAUTH2 SUCCESS HANDLER GUARD EVIDENCE ===");
        Authentication stringPrincipalAuth = new TestingAuthenticationToken("standardUsername", "password");
        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> oAuth2Handler.onAuthenticationSuccess(request, response, stringPrincipalAuth))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ClassCastException.class)
                .hasMessageStartingWith("Expected principal of type OAuth2User, but found: String")
                .satisfies(ex -> {
                    System.out.println("Exception: " + ex.getClass().getName());
                    System.out.println("Message: " + ex.getMessage());
                });

        System.out.println("Proof: OAuth2AuthenticationSuccessHandler guarded against non-OAuth2User principal");
    }

    @Test
    void processesCookieIntentAndClearsItAfterSuccessfulAuthentication() throws Exception {
        AuthService authService = Mockito.mock(AuthService.class);
        ServletResponseUtil responseUtil = Mockito.mock(ServletResponseUtil.class);
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(authService, responseUtil);
        OAuth2User principal = Mockito.mock(OAuth2User.class);
        JwtResponseDTO tokens = JwtResponseDTO.builder().accessToken("access").refreshToken("refresh").build();
        when(authService.processOAuth2Success(eq(principal), eq("ARTISAN"), any())).thenReturn(tokens);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(OAuthCookie.Intent.NAME.value(), "ARTISAN"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(principal, "credentials"));

        verify(authService).processOAuth2Success(eq(principal), eq("ARTISAN"), eq(request));
        verify(responseUtil).writeResponse(eq(response), eq(200), any());
        assertThat(response.getHeader("Set-Cookie")).contains(OAuthCookie.Intent.NAME.value() + "=", "Max-Age=0");
    }

    @Test
    void consumesSessionIntentWhenCookieIsAbsentAndAllowsNoIntent() throws Exception {
        AuthService authService = Mockito.mock(AuthService.class);
        ServletResponseUtil responseUtil = Mockito.mock(ServletResponseUtil.class);
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(authService, responseUtil);
        OAuth2User principal = Mockito.mock(OAuth2User.class);
        when(authService.processOAuth2Success(eq(principal), any(), any())).thenReturn(new JwtResponseDTO());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(OAuthCookie.Intent.NAME.value(), "CLIENT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(principal, "credentials"));

        verify(authService).processOAuth2Success(eq(principal), eq("CLIENT"), eq(request));
        assertThat(request.getSession().getAttribute(OAuthCookie.Intent.NAME.value())).isNull();

        MockHttpServletRequest noIntentRequest = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(noIntentRequest, new MockHttpServletResponse(), new TestingAuthenticationToken(principal, "credentials"));
        verify(authService).processOAuth2Success(eq(principal), eq(null), eq(noIntentRequest));
    }

    @Test
    void ignoresUnrelatedCookiesBeforeFallingBackToNoIntent() throws Exception {
        AuthService authService = Mockito.mock(AuthService.class);
        ServletResponseUtil responseUtil = Mockito.mock(ServletResponseUtil.class);
        OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(authService, responseUtil);
        OAuth2User principal = Mockito.mock(OAuth2User.class);
        when(authService.processOAuth2Success(eq(principal), eq(null), any())).thenReturn(new JwtResponseDTO());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("OTHER", "value"));

        handler.onAuthenticationSuccess(request, new MockHttpServletResponse(), new TestingAuthenticationToken(principal, "credentials"));

        verify(authService).processOAuth2Success(eq(principal), eq(null), eq(request));
    }
}
