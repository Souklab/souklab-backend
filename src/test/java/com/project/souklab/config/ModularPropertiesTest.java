package com.project.souklab.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated unit tests verifying empty Java defaults and property binding for
 * modular configuration property classes.
 */
class ModularPropertiesTest {

    @Test
    @DisplayName("AppStorageProperties resolves and validates the file-serving prefix")
    void storageResolvesFileServingPrefixAndUrls() {
        AppStorageProperties storage = new AppStorageProperties();

        assertThatThrownBy(storage::resolveFileServingPrefix)
                .isInstanceOf(IllegalStateException.class);

        storage.setFileServingPrefix("/files");
        assertThat(storage.resolveFileServingPrefix()).isEqualTo("/files/");
        assertThat(storage.toUrl("image.jpg")).isEqualTo("/files/image.jpg");
        assertThat(storage.toUrl(null)).isNull();
        assertThat(storage.toUrl(" ")).isNull();

        storage.setFileServingPrefix("/files/");
        assertThat(storage.resolveFileServingPrefix()).isEqualTo("/files/");
        assertThat(storage.toUrl("image.jpg")).isEqualTo("/files/image.jpg");
    }

    @Test
    void storageRejectsBlankFileServingPrefix() {
        AppStorageProperties storage = new AppStorageProperties();
        storage.setFileServingPrefix(" ");

        assertThatThrownBy(storage::resolveFileServingPrefix)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file-serving-prefix");
    }

    @Test
    @DisplayName("Modular properties do not provide unexpected defaults in Java")
    void policyDefaultsAreNotProvidedByJava() {
        SupportProperties support = new SupportProperties();
        assertThat(support.getEmail()).isNull();
        assertThat(support.getContactMessage()).isNull();

        NotificationProperties notification = new NotificationProperties();
        assertThat(notification.getMaxMessageLength()).isZero();

        FeedProperties feed = new FeedProperties();
        assertThat(feed.getMaxMediaPerPost()).isZero();
        assertThat(feed.getAllowedImageMimeTypes()).isNull();

        AdminProperties admin = new AdminProperties();
        assertThat(admin.getDefaultBanReason()).isNull();
        assertThat(admin.getDefaultTimeoutReason()).isNull();

        OAuthProperties oauth = new OAuthProperties();
        assertThat(oauth.getIntentCookieMaxAgeSeconds()).isZero();

        AuthProperties auth = new AuthProperties();
        assertThat(auth.getVerification()).isNotNull();
        assertThat(auth.getVerification().getMaxAttempts()).isZero();
        assertThat(auth.getVerification().getExpirationMinutes()).isZero();
        assertThat(auth.getVerification().getCodeLength()).isZero();

        ArtisanProperties artisan = new ArtisanProperties();
        assertThat(artisan.getFormateur()).isNotNull();
        assertThat(artisan.getFormateur().getReapplyCooldownDays()).isZero();

        EmailProperties email = new EmailProperties();
        assertThat(email.getFrom()).isNull();
        assertThat(email.isUseSmtp()).isFalse();

        FavoritesProperties favorites = new FavoritesProperties();
        assertThat(favorites.getMaxPerClient()).isZero();
    }

    @Test
    @DisplayName("Modular properties bind custom values from environment / property sources")
    void bindsCustomConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-app-properties", Map.ofEntries(
                Map.entry("app.support.email", "help@customdomain.com"),
                Map.entry("app.support.contact-message", "Please reach out to support team."),
                Map.entry("app.email.from", "testingemailjavawalid@gmail.com"),
                Map.entry("app.email.use-smtp", "true"),
                Map.entry("app.notification.max-message-length", "2048"),
                Map.entry("app.feed.max-media-per-post", "12"),
                Map.entry("app.feed.allowed-image-mime-types", "image/jpeg,image/webp"),
                Map.entry("app.admin.default-ban-reason", "Violated community guidelines"),
                Map.entry("app.admin.default-timeout-reason", "Temporary suspension"),
                Map.entry("app.oauth.intent-cookie-max-age-seconds", "600"),
                Map.entry("app.auth.verification.max-attempts", "3"),
                Map.entry("app.auth.verification.expiration-minutes", "30"),
                Map.entry("app.auth.verification.code-length", "8"),
                Map.entry("app.artisan.formateur.reapply-cooldown-days", "30"),
                Map.entry("app.async.application.core-pool-size", "2"),
                Map.entry("app.async.application.max-pool-size", "8"),
                Map.entry("app.async.application.queue-capacity", "50"),
                Map.entry("app.async.application.thread-name-prefix", "custom-async-"),
                Map.entry("app.cache.expire-after-write", "15m"),
                Map.entry("app.cache.maximum-size", "250"),
                Map.entry("app.search.mass-indexing.threads-to-load-objects", "3"),
                Map.entry("app.search.mass-indexing.batch-size-to-load-objects", "40"),
                Map.entry("app.search.mass-indexing.id-fetch-size", "75"),
                Map.entry("app.favorites.max-per-client", "250")
        )));

        Binder binder = new Binder(ConfigurationPropertySources.from(environment.getPropertySources()));

        SupportProperties support = binder.bind("app.support", SupportProperties.class).get();
        assertThat(support.getEmail()).isEqualTo("help@customdomain.com");
        assertThat(support.getContactMessage()).isEqualTo("Please reach out to support team.");

        EmailProperties email = binder.bind("app.email", EmailProperties.class).get();
        assertThat(email.getFrom()).isEqualTo("testingemailjavawalid@gmail.com");
        assertThat(email.isUseSmtp()).isTrue();

        NotificationProperties notification = binder.bind("app.notification", NotificationProperties.class).get();
        assertThat(notification.getMaxMessageLength()).isEqualTo(2048);

        FeedProperties feed = binder.bind("app.feed", FeedProperties.class).get();
        assertThat(feed.getMaxMediaPerPost()).isEqualTo(12);
        assertThat(feed.getAllowedImageMimeTypes()).containsExactly("image/jpeg", "image/webp");

        AdminProperties admin = binder.bind("app.admin", AdminProperties.class).get();
        assertThat(admin.getDefaultBanReason()).isEqualTo("Violated community guidelines");
        assertThat(admin.getDefaultTimeoutReason()).isEqualTo("Temporary suspension");

        OAuthProperties oauth = binder.bind("app.oauth", OAuthProperties.class).get();
        assertThat(oauth.getIntentCookieMaxAgeSeconds()).isEqualTo(600);

        AuthProperties auth = binder.bind("app.auth", AuthProperties.class).get();
        assertThat(auth.getVerification().getMaxAttempts()).isEqualTo(3);
        assertThat(auth.getVerification().getExpirationMinutes()).isEqualTo(30);
        assertThat(auth.getVerification().getCodeLength()).isEqualTo(8);

        ArtisanProperties artisan = binder.bind("app.artisan", ArtisanProperties.class).get();
        assertThat(artisan.getFormateur().getReapplyCooldownDays()).isEqualTo(30L);

        FavoritesProperties favorites = binder.bind("app.favorites", FavoritesProperties.class).get();
        assertThat(favorites.getMaxPerClient()).isEqualTo(250);

        AsyncProperties async = binder.bind("app.async", AsyncProperties.class).get();
        assertThat(async.getApplication().getCorePoolSize()).isEqualTo(2);
        assertThat(async.getApplication().getMaxPoolSize()).isEqualTo(8);
        assertThat(async.getApplication().getQueueCapacity()).isEqualTo(50);
        assertThat(async.getApplication().getThreadNamePrefix()).isEqualTo("custom-async-");

        CacheProperties cache = binder.bind("app.cache", CacheProperties.class).get();
        assertThat(cache.getExpireAfterWrite()).hasMinutes(15);
        assertThat(cache.getMaximumSize()).isEqualTo(250);

        SearchProperties search = binder.bind("app.search", SearchProperties.class).get();
        assertThat(search.getMassIndexing().getThreadsToLoadObjects()).isEqualTo(3);
        assertThat(search.getMassIndexing().getBatchSizeToLoadObjects()).isEqualTo(40);
        assertThat(search.getMassIndexing().getIdFetchSize()).isEqualTo(75);
    }
}
