package com.project.souklab.controller.support;

import tools.jackson.databind.json.JsonMapper;
import com.project.souklab.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import com.project.souklab.config.AdminProperties;
import com.project.souklab.config.AnalyticsExportProperties;
import com.project.souklab.config.AnalyticsJobProperties;
import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.config.AnalyticsRabbitProperties;
import com.project.souklab.config.AnalyticsRetentionProperties;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.config.AsyncProperties;
import com.project.souklab.config.AuthProperties;
import com.project.souklab.config.AvatarProperties;
import com.project.souklab.config.CacheProperties;
import com.project.souklab.config.ChargilyProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.config.CorsProperties;
import com.project.souklab.config.DirectoryProperties;
import com.project.souklab.config.EmailProperties;
import com.project.souklab.config.FavoritesProperties;
import com.project.souklab.config.FeedProperties;
import com.project.souklab.config.FormationProperties;
import com.project.souklab.config.HealthProperties;
import com.project.souklab.config.JwtProperties;
import com.project.souklab.config.MailerSendProperties;
import com.project.souklab.config.NotificationProperties;
import com.project.souklab.config.OAuthProperties;
import com.project.souklab.config.OpenApiProperties;
import com.project.souklab.config.RateLimitEndpointProperties;
import com.project.souklab.config.RateLimitProperties;
import com.project.souklab.config.RelayProperties;
import com.project.souklab.config.SearchProperties;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.config.SupportProperties;
import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.security.AccessControlService;
import com.project.souklab.security.Permission;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Shared test security configuration for controller slice tests.
 * Enables method security for @PreAuthorize evaluations, disables CSRF to match
 * production stateless token architecture, and installs an authentication entry point
 * that returns standard 401 ApiResponse JSON.
 */
@TestConfiguration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({
        AdminProperties.class,
        AnalyticsExportProperties.class,
        AnalyticsJobProperties.class,
        AnalyticsProperties.class,
        AnalyticsRabbitProperties.class,
        AnalyticsRetentionProperties.class,
        AppStorageProperties.class,
        ArtisanProperties.class,
        AsyncProperties.class,
        AuthProperties.class,
        AvatarProperties.class,
        CacheProperties.class,
        ChargilyProperties.class,
        ChatProperties.class,
        CorsProperties.class,
        DirectoryProperties.class,
        EmailProperties.class,
        FavoritesProperties.class,
        FeedProperties.class,
        FormationProperties.class,
        HealthProperties.class,
        JwtProperties.class,
        MailerSendProperties.class,
        NotificationProperties.class,
        OAuthProperties.class,
        OpenApiProperties.class,
        RateLimitEndpointProperties.class,
        RateLimitProperties.class,
        RelayProperties.class,
        SearchProperties.class,
        StorageProperties.class,
        SubscriptionProperties.class,
        SupportProperties.class
})
public class ControllerSliceSecurityConfig {


    /**
     * Controller slices exercise HTTP permission wiring without loading the persistence layer.
     * The production access-control component is tested separately with persisted accounts.
     */
    @Bean("accessControl")
    public AccessControlService accessControlService() {
        AccessControlService service = Mockito.mock(AccessControlService.class);
        when(service.hasPermission(any(), any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), invocation.getArgument(1)));
        when(service.isAdmin(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.USERS));
        when(service.canManageUsers(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.USERS));
        when(service.canManageFormations(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.FORMATIONS));
        when(service.canModerateFeed(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.FEED));
        when(service.canModerateReports(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.REPORTS));
        when(service.canManageFinancialOperations(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Financial.ADMIN));
        when(service.canViewAnalytics(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Analytics.ADMIN));
        when(service.canManageArtisanFormations(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Artisan.FORMATIONS));
        when(service.canManageArtisanContent(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Artisan.CONTENT));
        when(service.canManageArtisanReviews(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Artisan.REVIEWS));
        when(service.isArtisan(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Artisan.CONTENT));
        when(service.canReadProfile(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Profile.READ));
        when(service.canWriteProfile(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Profile.WRITE));
        when(service.canManageCatalog(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Admin.CATALOG));
        when(service.canManageFavorites(any())).thenAnswer(invocation -> hasPermission(
                invocation.getArgument(0), Permission.Client.FAVORITES));
        return service;
    }

    private static boolean hasPermission(Authentication authentication, Permission permission) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(permission::matches);
    }

    /**
     * Configures the test security filter chain.
     */
    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
                                "/api/v1/catalog/**",
                                "/api/v1/public/**",
                                "/api/v1/subscriptions/plans",
                                "/api/v1/subscriptions/plans/**",
                                "/api/v1/integrations/chargily/webhook"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/artisan/formations/*/reviews",
                                "/api/v1/artisan/reviews/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            ApiResponse<Void> apiResponse = ApiResponse.error("UNAUTHORIZED", "Full authentication is required to access this resource");
                            apiResponse.setCode(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(jsonMapper.writeValueAsString(apiResponse));
                            response.getWriter().flush();
                        })
                );
        return http.build();
    }
}
