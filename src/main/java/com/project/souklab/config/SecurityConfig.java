package com.project.souklab.config;

import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.filestorage.security.FileRateLimitFilter;
import com.project.souklab.security.AvatarUploadRateLimitFilter;
import com.project.souklab.security.AvatarUploadSizeFilter;
import com.project.souklab.security.ChargilyWebhookSizeFilter;
import com.project.souklab.security.JwtAuthenticationFilter;
import com.project.souklab.security.OAuth2AuthenticationFailureHandler;
import com.project.souklab.security.OAuth2AuthenticationSuccessHandler;
import com.project.souklab.security.RateLimitFilter;
import com.project.souklab.security.UserRateLimitFilter;
import com.project.souklab.security.RateLimitBucketStore;
import com.project.souklab.util.ServletResponseUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(0);
        return registration;
    }

    private final CorsProperties corsProperties;
    private final ChargilyProperties chargilyProperties;
    private final StorageProperties storageProperties;
    private final AvatarProperties avatarProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private UserRateLimitFilter userRateLimitFilter;
    private final FileRateLimitFilter fileRateLimitFilter;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final ServletResponseUtil servletResponseUtil;
    private RateLimitBucketStore rateLimitBucketStore = RateLimitBucketStore.inMemory();

    @Autowired(required = false)
    void setOAuth2AuthenticationFailureHandler(OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler) {
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
    }

    @Autowired(required = false)
    void setRateLimitBucketStore(RateLimitBucketStore rateLimitBucketStore) {
        this.rateLimitBucketStore = rateLimitBucketStore;
    }

    @Autowired(required = false)
    void setUserRateLimitFilter(UserRateLimitFilter userRateLimitFilter) {
        this.userRateLimitFilter = userRateLimitFilter;
    }

    @Bean
    public AvatarUploadSizeFilter avatarUploadSizeFilter() {
        return new AvatarUploadSizeFilter(storageProperties, servletResponseUtil);
    }

    @Bean
    public AvatarUploadRateLimitFilter avatarUploadRateLimitFilter() {
        return new AvatarUploadRateLimitFilter(servletResponseUtil, avatarProperties, rateLimitBucketStore);
    }

    @Bean
    public ChargilyWebhookSizeFilter chargilyWebhookSizeFilter() {
        return new ChargilyWebhookSizeFilter(chargilyProperties, servletResponseUtil);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration() {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<FileRateLimitFilter> fileRateLimitFilterRegistration() {
        FilterRegistrationBean<FileRateLimitFilter> registration = new FilterRegistrationBean<>(fileRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AvatarUploadSizeFilter> avatarUploadSizeFilterRegistration(AvatarUploadSizeFilter filter) {
        FilterRegistrationBean<AvatarUploadSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AvatarUploadRateLimitFilter> avatarUploadRateLimitFilterRegistration(AvatarUploadRateLimitFilter filter) {
        FilterRegistrationBean<AvatarUploadRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ChargilyWebhookSizeFilter> chargilyWebhookSizeFilterRegistration(ChargilyWebhookSizeFilter filter) {
        FilterRegistrationBean<ChargilyWebhookSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * Configures the security filter chain for stateless JWT authentication.
     * CSRF protection is intentionally disabled — this API is stateless and
     * token-authenticated (JWT Bearer), not cookie/session-based, so CSRF does not apply.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/**"))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER
                        ))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none';")
                        )
                        .permissionsPolicyHeader(permissions -> permissions
                                .policy("camera=(), microphone=(), geolocation=()")
                        ))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/feed",
                                "/api/v1/feed/**",
                                "/api/v1/catalog/**",
                                "/api/v1/public/**",
                                "/api/v1/subscriptions/plans",
                                "/api/v1/subscriptions/plans/**",
                                "/api/v1/artisans/*/reviews",
                                "/api/v1/artisan/reviews/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/feed/*/share").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/oauth/**",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/error",
                                "/ws/**",
                                "/api/v1/integrations/chargily/webhook"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> {
                    oauth2.successHandler(oAuth2AuthenticationSuccessHandler);
                    if (oAuth2AuthenticationFailureHandler != null) {
                        oauth2.failureHandler(oAuth2AuthenticationFailureHandler);
                    }
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                servletResponseUtil.writeResponse(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        ApiResponse.error(ApiErrorCode.UNAUTHORIZED,
                                                "Unauthorized: Full authentication is required to access this resource")
                                )
                        )
                );

        http.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        if (userRateLimitFilter != null) {
            http.addFilterAfter(userRateLimitFilter, JwtAuthenticationFilter.class);
        }
        http.addFilterAfter(fileRateLimitFilter, JwtAuthenticationFilter.class);
        http.addFilterAfter(avatarUploadSizeFilter(), JwtAuthenticationFilter.class);
        http.addFilterAfter(avatarUploadRateLimitFilter(), AvatarUploadSizeFilter.class);
        http.addFilterBefore(chargilyWebhookSizeFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(Arrays.stream(new HttpMethod[]{
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                HttpMethod.DELETE, HttpMethod.OPTIONS
        }).map(HttpMethod::toString).toList());
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        configuration.setExposedHeaders(List.of("Authorization", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
