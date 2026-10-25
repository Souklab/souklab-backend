package com.project.souklab.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.function.Consumer;

import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.model.analytics.AnalyticsBucket;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigurationPolicyValidatorTest {
    @Test
    void acceptsCompleteSafeConfiguration() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> fixture.validator.validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidCoreAndStoragePolicies() {
        assertInvalid(f -> f.avatar.setMaxPerUser(0), "avatar.max-per-user");
        assertInvalid(f -> f.avatar.setAllowedMimeTypes(List.of(" ")), "avatar.allowed-mime-types");
        assertInvalid(f -> f.storage.getValidation().setMaxFileSize(DataSize.ofBytes(0)), "storage.validation.max-file-size");
        assertInvalid(f -> f.storage.getValidation().setAllowedMimeTypes(List.of()), "storage.validation.allowed-mime-types");
        assertInvalid(f -> f.cors.setAllowedOrigins(List.of("*")), "app.cors.allowed-origins");
    }

    @Test
    void validatesRateLimitAsyncCacheAndIndexPolicies() {
        assertInvalid(f -> { f.avatar.getRateLimit().setEnabled(true); f.avatar.getRateLimit().setCapacity(0); }, "avatar.rate-limit.capacity");
        assertInvalid(f -> { f.avatar.getRateLimit().setEnabled(true); f.avatar.getRateLimit().setCapacity(1); f.avatar.getRateLimit().setRefillDuration(Duration.ZERO); }, "refill-duration");
        assertInvalid(f -> f.async.getApplication().setCorePoolSize(0), "app.async.application");
        assertInvalid(f -> f.cache.setMaximumSize(0), "app.cache");
        assertInvalid(f -> f.search.getMassIndexing().setIdFetchSize(0), "mass-indexing");
    }

    @Test
    void rejectsRemainingRateLimitAndExecutorVariants() {
        assertInvalid(f -> { f.avatar.getRateLimit().setEnabled(true); f.avatar.getRateLimit().setCapacity(1); f.avatar.getRateLimit().setRefillDuration(Duration.ofSeconds(-1)); }, "refill-duration");
        assertInvalid(f -> { f.avatar.getRateLimit().setEnabled(true); f.avatar.getRateLimit().setCapacity(1); f.avatar.getRateLimit().setRefillDuration(null); }, "refill-duration");
        assertInvalid(f -> f.async.getApplication().setMaxPoolSize(0), "app.async.application");
        assertInvalid(f -> f.async.getApplication().setCorePoolSize(3), "app.async.application");
        assertInvalid(f -> f.async.getApplication().setQueueCapacity(0), "app.async.application");
        assertInvalid(f -> f.async.getApplication().setThreadNamePrefix(" "), "app.async.application");
        assertInvalid(f -> f.async.setWorkflow(null), "app.async.workflow");
        assertInvalid(f -> f.async.getWorkflow().setThreadNamePrefix(null), "app.async.workflow");
    }

    @Test
    void rejectsInvalidEndpointUserOverride() {
        Fixture fixture = new Fixture();
        RateLimitEndpointProperties endpoints = new RateLimitEndpointProperties();
        endpoints.getAdminApi().setEnabled(true);
        endpoints.getAdminApi().setCapacity(10);
        endpoints.getAdminApi().setRefillDuration(Duration.ofMinutes(1));
        endpoints.getAdminApi().setUserCapacity(5);
        endpoints.getAdminApi().setUserRefillDuration(Duration.ZERO);
        fixture.validator.setRateLimitEndpointProperties(endpoints);

        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessageContaining("user-refill-duration");
    }

    @Test
    void validatesEnabledOpenApiContract() {
        Fixture fixture = new Fixture();
        OpenApiProperties openApi = new OpenApiProperties();
        openApi.setEnabled(true);
        openApi.setPath("/v3/api-docs");
        openApi.setSwaggerPath("/swagger-ui.html");
        openApi.setTitle("Souklab API");
        openApi.setVersion("1.0.0");
        fixture.validator.setOpenApiProperties(openApi);

        assertThatCode(() -> fixture.validator.validate()).doesNotThrowAnyException();
        openApi.setPath("v3/api-docs");
        assertThatThrownBy(() -> fixture.validator.validate()).hasMessageContaining("app.openapi.path");
    }

    @Test
    void rejectsEmptyOrDuplicateAnalyticsBuckets() {
        Fixture fixture = new Fixture();
        AnalyticsProperties analytics = validAnalyticsProperties();
        fixture.validator.setAnalyticsProperties(analytics);

        analytics.setSupportedBuckets(List.of());
        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessageContaining("app.analytics page, range, and bucket limits");

        analytics.setSupportedBuckets(List.of(AnalyticsBucket.DAY, AnalyticsBucket.DAY));
        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessageContaining("supported-buckets");
    }

    private AnalyticsProperties validAnalyticsProperties() {
        AnalyticsProperties analytics = new AnalyticsProperties();
        analytics.setDefaultPageSize(20);
        analytics.setMaximumPageSize(100);
        analytics.setMaximumRangeDays(366);
        analytics.setMaximumBucketCount(500);
        analytics.setSupportedBuckets(List.of(AnalyticsBucket.DAY, AnalyticsBucket.WEEK));
        analytics.setRollupBatchSize(100);
        analytics.setBackfillBatchSize(100);
        analytics.setQueryTimeout(Duration.ofSeconds(10));
        analytics.setJobRetention(Duration.ofHours(24));
        analytics.setBusinessTimeZone("Africa/Algiers");
        return analytics;
    }

    @Test
    void rejectsMissingAndMalformedNestedPolicies() {
        assertInvalid(f -> f.storage.setValidation(null), "storage.validation.max-file-size");
        assertInvalid(f -> f.async = null, "app.async");
        assertInvalid(f -> f.cache.setExpireAfterWrite(null), "app.cache");
        assertInvalid(f -> f.search.setMassIndexing(null), "mass-indexing");
        assertInvalid(f -> f.cors.setAllowedOrigins(List.of(" ")), "app.cors.allowed-origins");
        assertInvalid(f -> f.cors.setAllowedOrigins(new ArrayList<>(Collections.singletonList(null))), "app.cors.allowed-origins");
        assertInvalid(f -> f.cors.setAllowedOrigins(List.of()), "app.cors.allowed-origins");
        assertInvalid(f -> f.search.getMassIndexing().setThreadsToLoadObjects(-1), "mass-indexing");
        assertInvalid(f -> f.search.getMassIndexing().setBatchSizeToLoadObjects(-1), "mass-indexing");
        assertInvalid(f -> f.cache.setExpireAfterWrite(Duration.ofSeconds(-1)), "app.cache");
        assertInvalid(f -> f.directory = null, "app.directory");
        assertInvalid(f -> f.directory.setDefaultPageIndex(-1), "app.directory");
        assertInvalid(f -> f.directory.setDefaultPageSize(0), "app.directory");
        assertInvalid(f -> f.directory.setMaxPageSize(0), "app.directory");
        assertInvalid(f -> f.cors.setAllowedOrigins(null), "app.cors.allowed-origins");
        assertInvalid(f -> f.async.setApplication(null), "app.async.application");
        assertInvalid(f -> f.async.setWorkflow(null), "app.async.workflow");
        assertInvalid(f -> f.cache = null, "app.cache");
        assertInvalid(f -> f.search.getMassIndexing().setThreadsToLoadObjects(0), "mass-indexing");
        assertInvalid(f -> f.search.getMassIndexing().setBatchSizeToLoadObjects(0), "mass-indexing");
        assertInvalid(f -> f.search.getMassIndexing().setIdFetchSize(-1), "mass-indexing");
        assertInvalid(f -> f.directory.setDefaultPageSize(0), "app.directory");
        assertInvalid(f -> { f.directory.setMinPageSize(10); f.directory.setDefaultPageSize(5); }, "app.directory");
        assertInvalid(f -> { f.directory.setMinPageSize(10); f.directory.setMaxPageSize(5); }, "app.directory");
    }

    @Test
    void rejectsInvalidFavoritesPolicy() {
        assertInvalid(f -> f.favorites.setMaxPerClient(0), "app.favorites.max-per-client");
    }

    @Test
    void rejectsMissingFileServingPrefix() {
        Fixture fixture = new Fixture();
        fixture.appStorage.setFileServingPrefix(null);

        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessage("app.storage.file-serving-prefix must be configured");
    }

    @Test
    void rejectsProductionBucketAutoCreation() {
        Fixture fixture = new Fixture();
        fixture.storage.getS3().setAutoCreateBucket(true);
        when(fixture.environment.matchesProfiles("prod", "production")).thenReturn(true);

        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessageContaining("auto-create-bucket");
    }

    @Test
    void rejectsAllZeroWebhookEncryptionKeyInProduction() {
        Fixture fixture = new Fixture();
        ChargilyProperties chargily = new ChargilyProperties();
        chargily.setEnabled(true);
        chargily.setApiKey("test_api_key");
        chargily.setSecretKey("test_secret_key");
        chargily.setBaseUrl("https://pay.chargily.net/api/v2");
        chargily.setWebhookUrl("https://souklab.dz/api/v1/integrations/chargily/webhook");
        chargily.setSuccessUrl("https://souklab.dz/subscription/success");
        chargily.setFailureUrl("https://souklab.dz/subscription/failure");
        chargily.setConnectTimeout(Duration.ofSeconds(5));
        chargily.setReadTimeout(Duration.ofSeconds(10));
        chargily.setResponseTimeout(Duration.ofSeconds(10));
        chargily.setRetryBackoff(Duration.ofSeconds(1));
        chargily.setRetryCount(3);
        chargily.setRequestBodyLimit(65536);
        chargily.setCurrency("DZD");
        chargily.setWebhookEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        fixture.chargily = chargily;
        fixture.rebuildValidator();

        when(fixture.environment.matchesProfiles("prod", "production")).thenReturn(false);
        assertThatCode(() -> fixture.validator.validate()).doesNotThrowAnyException();

        when(fixture.environment.matchesProfiles("prod", "production")).thenReturn(true);
        assertThatThrownBy(() -> fixture.validator.validate())
                .hasMessageContaining("app.chargily.webhook-encryption-key must not be all zeros in production");
    }

    private void assertInvalid(Consumer<Fixture> change, String message) {
        Fixture fixture = new Fixture();
        change.accept(fixture);
        fixture.rebuildValidator();
        assertThatThrownBy(() -> fixture.validator.validate()).hasMessageContaining(message);
    }

    private static final class Fixture {
        RateLimitProperties rateLimit = new RateLimitProperties();
        AppStorageProperties appStorage = new AppStorageProperties();
        CorsProperties cors = new CorsProperties();
        AsyncProperties async = new AsyncProperties();
        CacheProperties cache = new CacheProperties();
        SearchProperties search = new SearchProperties();
        DirectoryProperties directory = new DirectoryProperties();
        FavoritesProperties favorites = new FavoritesProperties();
        SubscriptionProperties subscription = new SubscriptionProperties();
        ChargilyProperties chargily = new ChargilyProperties();
        MailerSendProperties mailerSend = new MailerSendProperties();
        RelayProperties relay = new RelayProperties();
        AvatarProperties avatar = new AvatarProperties();
        StorageProperties storage = new StorageProperties();
        Environment environment = mock(Environment.class);
        ConfigurationPolicyValidator validator;

        Fixture() {
            avatar.setMaxPerUser(3);
            avatar.setAllowedMimeTypes(List.of("image/jpeg"));
            storage.getValidation().setMaxFileSize(DataSize.ofMegabytes(1));
            storage.getValidation().setAllowedMimeTypes(List.of("image/jpeg"));
            cors.setAllowedOrigins(List.of("http://localhost"));
            appStorage.setFileServingPrefix("/api/v1/files/");
            configureExecutor(async.getApplication());
            configureExecutor(async.getWorkflow());
            cache.setMaximumSize(10);
            cache.setExpireAfterWrite(Duration.ofMinutes(1));
            search.getMassIndexing().setThreadsToLoadObjects(1);
            search.getMassIndexing().setBatchSizeToLoadObjects(1);
            search.getMassIndexing().setIdFetchSize(1);
            directory.setDefaultPageIndex(0);
            directory.setDefaultPageSize(20);
            directory.setMinPageSize(1);
            directory.setMaxPageSize(100);
            favorites.setMaxPerClient(500);
            when(environment.matchesProfiles("prod", "production")).thenReturn(false);
            rebuildValidator();
        }

        void rebuildValidator() {
            validator = new ConfigurationPolicyValidator(
                    rateLimit, appStorage, cors, async, cache, search, directory,
                    favorites, subscription, chargily, mailerSend, relay,
                    avatar, storage, environment);
        }

        private void configureExecutor(AsyncProperties.Executor executor) {
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(2);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("test-");
        }
    }
}
