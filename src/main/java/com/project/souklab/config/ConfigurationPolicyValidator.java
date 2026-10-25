package com.project.souklab.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.model.CurrencyCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;

/** Fails fast on unsafe or unusable runtime policy configuration. */
@Component
@RequiredArgsConstructor
public class ConfigurationPolicyValidator {

    private final RateLimitProperties rateLimitProperties;
    private final AppStorageProperties appStorageProperties;
    private final CorsProperties corsProperties;
    private final AsyncProperties asyncProperties;
    private final CacheProperties cacheProperties;
    private final SearchProperties searchProperties;
    private final DirectoryProperties directoryProperties;
    private final FavoritesProperties favoritesProperties;
    private final SubscriptionProperties subscriptionProperties;
    private final ChargilyProperties chargilyProperties;
    private final MailerSendProperties mailerSendProperties;
    private final RelayProperties relayProperties;
    private final AvatarProperties avatarProperties;
    private final StorageProperties storageProperties;
    private final Environment environment;
    private AnalyticsProperties analyticsProperties;
    private AnalyticsRetentionProperties analyticsRetentionProperties;
    private AnalyticsRabbitProperties analyticsRabbitProperties;
    private RateLimitEndpointProperties rateLimitEndpointProperties;
    private AnalyticsJobProperties analyticsJobProperties;
    private AnalyticsExportProperties analyticsExportProperties;
    private HealthProperties healthProperties;
    private OpenApiProperties openApiProperties;

    @Autowired(required = false)
    void setAnalyticsProperties(AnalyticsProperties value) { this.analyticsProperties = value; }

    @Autowired(required = false)
    void setAnalyticsRetentionProperties(AnalyticsRetentionProperties value) { this.analyticsRetentionProperties = value; }

    @Autowired(required = false)
    void setAnalyticsRabbitProperties(AnalyticsRabbitProperties value) { this.analyticsRabbitProperties = value; }

    @Autowired(required = false)
    void setRateLimitEndpointProperties(RateLimitEndpointProperties value) { this.rateLimitEndpointProperties = value; }

    @Autowired(required = false)
    void setAnalyticsJobProperties(AnalyticsJobProperties value) { this.analyticsJobProperties = value; }

    @Autowired(required = false)
    void setAnalyticsExportProperties(AnalyticsExportProperties value) { this.analyticsExportProperties = value; }

    @Autowired(required = false)
    void setHealthProperties(HealthProperties value) { this.healthProperties = value; }

    @Autowired(required = false)
    void setOpenApiProperties(OpenApiProperties value) { this.openApiProperties = value; }

    @PostConstruct
    void validate() {
        requirePositive("avatar.max-per-user", avatarProperties.getMaxPerUser());
        requireNonEmpty("avatar.allowed-mime-types", avatarProperties.getAllowedMimeTypes());
        validateRateLimit("avatar.rate-limit", avatarProperties.getRateLimit());
        if (rateLimitProperties.isEnabled()) {
            requirePositive("app.rate-limit.capacity", rateLimitProperties.getCapacity());
            validatePositiveDuration("app.rate-limit.refill-duration", rateLimitProperties.getRefillDuration());
        }
        if (rateLimitProperties.isUserEnabled()) {
            if (rateLimitProperties.getUserCapacity() <= 0
                    || rateLimitProperties.getUserRefillDuration() == null
                    || rateLimitProperties.getUserRefillDuration().isZero()
                    || rateLimitProperties.getUserRefillDuration().isNegative()) {
                throw new IllegalStateException("app.rate-limit user policy is invalid");
            }
        }

        StorageProperties.ValidationProperties validation = storageProperties.getValidation();
        if (validation == null || validation.getMaxFileSize() == null
                || validation.getMaxFileSize().toBytes() <= 0) {
            throw new IllegalStateException("storage.validation.max-file-size must be positive");
        }
        requireNonEmpty("storage.validation.allowed-mime-types", validation.getAllowedMimeTypes());
        requireConfigured("app.storage.file-serving-prefix", appStorageProperties.getFileServingPrefix());
        validateCors(corsProperties.getAllowedOrigins());
        validateAsync(asyncProperties);
        validateCache(cacheProperties);
        validateMassIndexing(searchProperties.getMassIndexing());
        validateDirectory(directoryProperties);
        requirePositive("app.favorites.max-per-client", favoritesProperties.getMaxPerClient());
        validateSubscriptionPolicy();
        validateAnalyticsPolicy();
        if (healthProperties != null) {
            validatePositiveDuration("app.health.dependency-timeout", healthProperties.getDependencyTimeout());
        }
        validateOpenApiPolicy();

        if (isProduction() && Boolean.TRUE.equals(storageProperties.getS3().getAutoCreateBucket())) {
            throw new IllegalStateException("storage.s3.auto-create-bucket must be false in production");
        }

        if (isProduction()) {
            validateProductionPolicy();
        }
    }

    private void validateAnalyticsPolicy() {
        if (analyticsProperties != null) {
            requirePositive("app.analytics.default-page-size", analyticsProperties.getDefaultPageSize());
            requirePositive("app.analytics.maximum-page-size", analyticsProperties.getMaximumPageSize());
            if (analyticsProperties.getDefaultPageSize() > analyticsProperties.getMaximumPageSize()
                    || analyticsProperties.getMaximumRangeDays() <= 0
                    || analyticsProperties.getMaximumBucketCount() <= 0
                    || analyticsProperties.getRollupBatchSize() <= 0
                    || analyticsProperties.getBackfillBatchSize() <= 0
                    || analyticsProperties.getSupportedBuckets() == null
                    || analyticsProperties.getSupportedBuckets().isEmpty()
                    || analyticsProperties.getSupportedBuckets().stream().anyMatch(bucket -> bucket == null)) {
                throw new IllegalStateException("app.analytics page, range, and bucket limits are invalid");
            }
            if (analyticsProperties.getSupportedBuckets().size() != analyticsProperties.getSupportedBuckets().stream().distinct().count()) {
                throw new IllegalStateException("app.analytics.supported-buckets must contain unique supported bucket values");
            }
            validatePositiveDuration("app.analytics.query-timeout", analyticsProperties.getQueryTimeout());
            validatePositiveDuration("app.analytics.job-retention", analyticsProperties.getJobRetention());
            requireConfigured("app.analytics.business-time-zone", analyticsProperties.getBusinessTimeZone());
            try { ZoneId.of(analyticsProperties.getBusinessTimeZone()); }
            catch (RuntimeException ex) { throw new IllegalStateException("app.analytics.business-time-zone must be valid", ex); }
        }
        if (analyticsJobProperties != null) {
            requirePositive("app.analytics.jobs.concurrency", analyticsJobProperties.getConcurrency());
            requirePositive("app.analytics.jobs.maximum-result-rows", analyticsJobProperties.getMaximumResultRows());
            requirePositive("app.analytics.jobs.maximum-result-bytes", analyticsJobProperties.getMaximumResultBytes());
        }
        if (analyticsExportProperties != null) {
            requireConfigured("app.analytics.exports.storage-prefix", analyticsExportProperties.getStoragePrefix());
            validatePositiveDuration("app.analytics.exports.retention", analyticsExportProperties.getRetention());
        }
        if (analyticsRetentionProperties != null) {
            validatePositiveDuration("app.analytics.retention.raw-events", analyticsRetentionProperties.getRawEvents());
            validatePositiveDuration("app.analytics.retention.jobs", analyticsRetentionProperties.getJobs());
            validatePositiveDuration("app.analytics.retention.rollups", analyticsRetentionProperties.getRollups());
            validatePositiveDuration("app.analytics.retention.cleanup-interval", analyticsRetentionProperties.getCleanupInterval());
            requirePositive("app.analytics.retention.cleanup-batch-size", analyticsRetentionProperties.getCleanupBatchSize());
        }
        if (analyticsRabbitProperties != null && analyticsRabbitProperties.isEnabled()) {
            requireConfigured("app.analytics.rabbit.host", analyticsRabbitProperties.getHost());
            if (analyticsRabbitProperties.getPort() <= 0 || analyticsRabbitProperties.getRelayBatchSize() <= 0
                    || analyticsRabbitProperties.getMaxAttempts() <= 0) {
                throw new IllegalStateException("app.analytics.rabbit port, relay-batch-size, and max-attempts must be positive");
            }
            requireConfigured("app.analytics.rabbit.exchange", analyticsRabbitProperties.getExchange());
            requireConfigured("app.analytics.rabbit.queue", analyticsRabbitProperties.getQueue());
            requireConfigured("app.analytics.rabbit.routing-key", analyticsRabbitProperties.getRoutingKey());
            requireConfigured("app.analytics.rabbit.dead-letter-exchange", analyticsRabbitProperties.getDeadLetterExchange());
            requireConfigured("app.analytics.rabbit.dead-letter-queue", analyticsRabbitProperties.getDeadLetterQueue());
            validatePositiveDuration("app.analytics.rabbit.retry-backoff", analyticsRabbitProperties.getRetryBackoff());
            validatePositiveDuration("app.analytics.rabbit.max-retry-backoff", analyticsRabbitProperties.getMaxRetryBackoff());
            validatePositiveDuration("app.analytics.rabbit.poll-interval", analyticsRabbitProperties.getPollInterval());
            validatePositiveDuration("app.analytics.rabbit.confirm-timeout", analyticsRabbitProperties.getConfirmTimeout());
            if (analyticsRabbitProperties.getRetryMultiplier() < 1.0) {
                throw new IllegalStateException("app.analytics.rabbit.retry-multiplier must be at least 1");
            }
        }
        if (rateLimitEndpointProperties != null) {
            validateRateLimitRule("app.rate-limit.endpoints.authentication", rateLimitEndpointProperties.getAuthentication());
            validateRateLimitRule("app.rate-limit.endpoints.public-api", rateLimitEndpointProperties.getPublicApi());
            validateRateLimitRule("app.rate-limit.endpoints.admin-api", rateLimitEndpointProperties.getAdminApi());
            validateRateLimitRule("app.rate-limit.endpoints.analytics-jobs", rateLimitEndpointProperties.getAnalyticsJobs());
            validateRateLimitRule("app.rate-limit.endpoints.analytics-results", rateLimitEndpointProperties.getAnalyticsResults());
            validateRateLimitRule("app.rate-limit.endpoints.csv-exports", rateLimitEndpointProperties.getCsvExports());
            validateRateLimitRule("app.rate-limit.endpoints.chargily-webhook", rateLimitEndpointProperties.getChargilyWebhook());
        }
    }

    private void validateOpenApiPolicy() {
        if (openApiProperties == null || !openApiProperties.isEnabled()) {
            return;
        }
        validateEndpointPath("app.openapi.path", openApiProperties.getPath());
        validateEndpointPath("app.openapi.swagger-path", openApiProperties.getSwaggerPath());
        requireConfigured("app.openapi.title", openApiProperties.getTitle());
        requireConfigured("app.openapi.version", openApiProperties.getVersion());
    }

    private void validateEndpointPath(String name, String path) {
        requireConfigured(name, path);
        if (!path.startsWith("/") || path.contains(" ") || path.contains("//")) {
            throw new IllegalStateException(name + " must be a normalized absolute endpoint path");
        }
    }

    private void validateRateLimitRule(String prefix, RateLimitRule rule) {
        if (rule != null && rule.isEnabled()) {
            requirePositive(prefix + ".capacity", rule.getCapacity());
            validatePositiveDuration(prefix + ".refill-duration", rule.getRefillDuration());
            if (rule.getUserCapacity() < 0) {
                throw new IllegalStateException(prefix + ".user-capacity must not be negative");
            }
            if (rule.getUserCapacity() > 0) {
                validatePositiveDuration(prefix + ".user-refill-duration", rule.getUserRefillDuration());
            } else if (rule.getUserRefillDuration() != null) {
                validatePositiveDuration(prefix + ".user-refill-duration", rule.getUserRefillDuration());
            }
        }
    }

    private void validateSubscriptionPolicy() {
        SubscriptionProperties subscription = subscriptionProperties;
        ChargilyProperties chargily = chargilyProperties;
        if (chargily != null && chargily.isEnabled()) {
            validateChargily(chargily);
        }
        if (subscription == null || !subscription.isEnabled()) {
            return;
        }
        if (!CurrencyCode.DZD.matches(subscription.getCurrency())) {
            throw new IllegalStateException("app.subscription.currency must be " + CurrencyCode.DZD.value());
        }
        if (subscription.getMinimumPlanAmount() <= 0
                || subscription.getMaximumPlanAmount() < subscription.getMinimumPlanAmount()) {
            throw new IllegalStateException("app.subscription plan amount bounds are invalid");
        }
        if (subscription.getLifecycleBatchSize() <= 0 || subscription.getReminderOffsets() == null
                || subscription.getReminderOffsets().stream().anyMatch(offset -> offset == null || offset <= 0)) {
            throw new IllegalStateException("app.subscription lifecycle settings are invalid");
        }
        validatePositiveDuration("app.subscription.lifecycle-interval", subscription.getLifecycleInterval());
        requireConfigured("app.subscription.lifecycle-time-zone", subscription.getLifecycleTimeZone());
        try {
            ZoneId.of(subscription.getLifecycleTimeZone());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("app.subscription.lifecycle-time-zone must be a valid time zone", exception);
        }
        validatePositiveDuration("app.subscription.checkout-idempotency-retention", subscription.getCheckoutIdempotencyRetention());
        validatePositiveDuration("app.subscription.webhook-retention", subscription.getWebhookRetention());
        if (chargily == null || !chargily.isEnabled()) {
            throw new IllegalStateException("enabled subscriptions require enabled Chargily configuration");
        }
        validateChargily(chargily);
    }

    private void validateChargily(ChargilyProperties chargily) {
        requireConfigured("app.chargily.api-key", chargily.getApiKey());
        requireConfigured("app.chargily.secret-key", chargily.getSecretKey());
        requireConfigured("app.chargily.base-url", chargily.getBaseUrl());
        requireConfigured("app.chargily.webhook-encryption-key", chargily.getWebhookEncryptionKey());
        validateUrl("app.chargily.base-url", chargily.getBaseUrl());
        validateUrl("app.chargily.webhook-url", chargily.getWebhookUrl());
        validateUrl("app.chargily.success-url", chargily.getSuccessUrl());
        validateUrl("app.chargily.failure-url", chargily.getFailureUrl());
        validatePositiveDuration("app.chargily.connect-timeout", chargily.getConnectTimeout());
        validatePositiveDuration("app.chargily.read-timeout", chargily.getReadTimeout());
        validatePositiveDuration("app.chargily.response-timeout", chargily.getResponseTimeout());
        validatePositiveDuration("app.chargily.retry-backoff", chargily.getRetryBackoff());
        if (chargily.getRetryCount() < 0 || chargily.getRequestBodyLimit() <= 0
                || !CurrencyCode.DZD.matches(chargily.getCurrency())) {
            throw new IllegalStateException("Chargily retry, request limit, or currency configuration is invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(chargily.getWebhookEncryptionKey());
            if (decoded.length != 32) {
                throw new IllegalStateException("app.chargily.webhook-encryption-key must decode to 32 bytes");
            }
            if (isProduction()) {
                boolean allZeros = true;
                for (byte b : decoded) {
                    if (b != 0) {
                        allZeros = false;
                        break;
                    }
                }
                if (allZeros) {
                    throw new IllegalStateException("app.chargily.webhook-encryption-key must not be all zeros in production");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("app.chargily.webhook-encryption-key must be valid Base64", exception);
        }
    }

    private void validateUrl(String name, String value) {
        requireConfigured(name, value);
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("not absolute");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be a valid absolute URL", exception);
        }
    }

    private void validateProductionPolicy() {
        requireExactValue("spring.jpa.hibernate.ddl-auto", environment.getProperty("spring.jpa.hibernate.ddl-auto"), "validate");
        requireExactValue("spring.jpa.show-sql", environment.getProperty("spring.jpa.show-sql"), "false");
        requireExactValue("spring.jpa.properties.hibernate.format_sql", environment.getProperty("spring.jpa.properties.hibernate.format_sql"), "false");
        requireExactValue("spring.flyway.enabled", environment.getProperty("spring.flyway.enabled"), "true");
        requireExactValue("spring.mail.properties.mail.debug", environment.getProperty("spring.mail.properties.mail.debug"), "false");
        requirePositiveProperty("spring.mail.properties.mail.smtp.connectiontimeout");
        requirePositiveProperty("spring.mail.properties.mail.smtp.timeout");
        requirePositiveProperty("spring.mail.properties.mail.smtp.writetimeout");
        requireExactValue("logging.level.root", environment.getProperty("logging.level.root"), "INFO");
        requireExactValue("logging.level.com.project.souklab", environment.getProperty("logging.level.com.project.souklab"), "INFO");
        requireExactValue("logging.level.org.hibernate.search", environment.getProperty("logging.level.org.hibernate.search"), "WARN");
        requireExactValue("management.endpoint.health.show-details", environment.getProperty("management.endpoint.health.show-details"), "when-authorized");
        requireExactValue("management.endpoints.web.exposure.include", environment.getProperty("management.endpoints.web.exposure.include"), "health,info,prometheus");
        requireExactValue("management.info.env.enabled", environment.getProperty("management.info.env.enabled"), "false");
        validatePositiveDuration("app.mailersend.connection-timeout", mailerSendProperties.getConnectionTimeout());
        validatePositiveDuration("app.mailersend.read-timeout", mailerSendProperties.getReadTimeout());
        requireExactValue("storage.provider", environment.getProperty("storage.provider"), "s3");
        requireExactValue("storage.virus-scan.enabled", environment.getProperty("storage.virus-scan.enabled"), "true");
        requireExactValue("storage.virus-scan.fail-open", environment.getProperty("storage.virus-scan.fail-open"), "false");
        requireExactValue("app.admin.bootstrap-enabled", environment.getProperty("app.admin.bootstrap-enabled"), "false");
        requireExactValue("app.rate-limit.backend", environment.getProperty("app.rate-limit.backend"), "redis");
        requireConfigured("app.rate-limit.redis.host", environment.getProperty("app.rate-limit.redis.host"));
        requirePositiveProperty("app.rate-limit.redis.port");
        requirePositiveProperty("app.rate-limit.redis.connection-timeout");
        requirePositiveProperty("app.rate-limit.redis.command-timeout");
        requireExactValue("app.search.enabled", environment.getProperty("app.search.enabled"), "true");
        if (searchProperties.isSchemaBootstrapEnabled()) {
            requireExactValue("app.search.schema-management", searchProperties.getSchemaManagement(), "create-or-update");
        } else {
            requireExactValue("app.search.schema-management", searchProperties.getSchemaManagement(), "validate");
        }
        requireConfigured("app.search.uris", searchProperties.getUris());
        requireConfigured("app.relay.host", relayProperties.getHost());
        requireConfigured("app.relay.client-login", relayProperties.getClientLogin());
        requireConfigured("app.relay.client-passcode", relayProperties.getClientPasscode());
        requireConfigured("app.relay.system-login", relayProperties.getSystemLogin());
        requireConfigured("app.relay.system-passcode", relayProperties.getSystemPasscode());
    }

    private void requireExactValue(String name, String actual, String expected) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException(name + " must be " + expected + " in production");
        }
    }

    private void validateRateLimit(String prefix, AvatarProperties.RateLimitProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        requirePositive(prefix + ".capacity", properties.getCapacity());
        if (properties.getRefillDuration() == null || properties.getRefillDuration().isZero()
                || properties.getRefillDuration().isNegative()) {
            throw new IllegalStateException(prefix + ".refill-duration must be positive");
        }
    }

    private void validateCors(List<String> origins) {
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(origin -> origin == null
                || origin.isBlank() || origin.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must contain explicit origins when credentials are enabled");
        }
    }

    private boolean isProduction() {
        return environment.matchesProfiles("prod", "production");
    }

    private void validateAsync(AsyncProperties async) {
        if (async == null) {
            throw new IllegalStateException("app.async must be configured");
        }
        validateExecutor("app.async.application", async.getApplication());
        validateExecutor("app.async.workflow", async.getWorkflow());
    }

    private void validateExecutor(String name, AsyncProperties.Executor executor) {
        if (executor == null || executor.getCorePoolSize() <= 0 || executor.getMaxPoolSize() <= 0
                || executor.getCorePoolSize() > executor.getMaxPoolSize()
                || executor.getQueueCapacity() <= 0
                || executor.getThreadNamePrefix() == null || executor.getThreadNamePrefix().isBlank()) {
            throw new IllegalStateException(name + " must define valid pool sizes, queue capacity, and thread prefix");
        }
    }

    private void validateCache(CacheProperties cache) {
        if (cache == null || cache.getMaximumSize() <= 0 || cache.getExpireAfterWrite() == null
                || cache.getExpireAfterWrite().isZero() || cache.getExpireAfterWrite().isNegative()) {
            throw new IllegalStateException("app.cache must define a positive size and expiration");
        }
    }

    private void validateMassIndexing(SearchProperties.MassIndexing indexing) {
        if (indexing == null || indexing.getThreadsToLoadObjects() <= 0
                || indexing.getBatchSizeToLoadObjects() <= 0 || indexing.getIdFetchSize() <= 0) {
            throw new IllegalStateException("app.search.mass-indexing values must be positive");
        }
    }

    private void validateDirectory(DirectoryProperties directory) {
        if (directory == null || directory.getDefaultPageIndex() < 0
                || directory.getDefaultPageSize() < directory.getMinPageSize()
                || directory.getMinPageSize() <= 0
                || directory.getMaxPageSize() < directory.getMinPageSize()) {
            throw new IllegalStateException("app.directory pagination values must be valid");
        }
    }

    private void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    private void requireNonEmpty(String name, List<String> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException(name + " must not be empty");
        }
    }

    private void requireConfigured(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
    }

    private void requirePositiveProperty(String name) {
        String value = environment.getProperty(name);
        try {
            if (value == null || Long.parseLong(value) <= 0) {
                throw new IllegalStateException(name + " must be positive in production");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be a positive integer in production", exception);
        }
    }

    private void validatePositiveDuration(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive in production");
        }
    }
}
