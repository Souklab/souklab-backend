package com.project.souklab.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated unit tests for Search configuration properties binding and verification.
 * Validates that SearchProperties contains no hardcoded Java defaults and that
 * configuration strictly flows from environment variables and application.yml.
 */
class SearchPropertiesTest {

    /**
     * Verifies that a newly instantiated SearchProperties object contains no hardcoded Java defaults.
     */
    @Test
    @DisplayName("SearchProperties uninitialized instance has no hardcoded Java defaults")
    void uninitializedInstanceHasNoJavaDefaults() {
        SearchProperties search = new SearchProperties();

        assertThat(search.isEnabled()).isFalse();
        assertThat(search.getUris()).isNull();
        assertThat(search.getUsername()).isNull();
        assertThat(search.getPassword()).isNull();
        assertThat(search.getConnectionTimeout()).isZero();
        assertThat(search.getReadTimeout()).isZero();
        assertThat(search.getIndexPrefix()).isNull();
        assertThat(search.getSchemaManagement()).isNull();
        assertThat(search.isSyncOnStartup()).isFalse();
    }

    /**
     * Verifies that SearchProperties successfully binds default configuration values from properties.
     */
    @Test
    @DisplayName("SearchProperties binds default configuration values from properties")
    void bindsDefaultConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", Map.of(
                "app.search.enabled", "true",
                "app.search.uris", "http://localhost:9200",
                "app.search.connection-timeout", "5000",
                "app.search.read-timeout", "30000",
                "app.search.index-prefix", "souklab_",
                "app.search.schema-management", "create-or-update",
                "app.search.sync-on-startup", "true"
        )));

        Binder binder = new Binder(ConfigurationPropertySources.from(environment.getPropertySources()));
        SearchProperties search = binder.bind("app.search", SearchProperties.class).get();

        assertThat(search.isEnabled()).isTrue();
        assertThat(search.getUris()).isEqualTo("http://localhost:9200");
        assertThat(search.getUsername()).isNull();
        assertThat(search.getPassword()).isNull();
        assertThat(search.getConnectionTimeout()).isEqualTo(5000);
        assertThat(search.getReadTimeout()).isEqualTo(30000);
        assertThat(search.getIndexPrefix()).isEqualTo("souklab_");
        assertThat(search.getSchemaManagement()).isEqualTo("create-or-update");
        assertThat(search.isSyncOnStartup()).isTrue();
    }

    /**
     * Verifies that SearchProperties successfully binds all properties when bound directly.
     */
    @Test
    @DisplayName("SearchProperties binds all properties from environment source directly")
    void bindsDirectSearchProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", Map.of(
                "app.search.enabled", "false",
                "app.search.uris", "http://elasticsearch.cluster.internal:9200",
                "app.search.username", "elastic_user",
                "app.search.password", "secret_pass_123",
                "app.search.connection-timeout", "8000",
                "app.search.read-timeout", "45000",
                "app.search.index-prefix", "souklab_test_",
                "app.search.schema-management", "validate",
                "app.search.sync-on-startup", "false"
        )));

        Binder binder = new Binder(ConfigurationPropertySources.from(environment.getPropertySources()));
        SearchProperties search = binder.bind("app.search", SearchProperties.class).get();

        assertThat(search.isEnabled()).isFalse();
        assertThat(search.getUris()).isEqualTo("http://elasticsearch.cluster.internal:9200");
        assertThat(search.getUsername()).isEqualTo("elastic_user");
        assertThat(search.getPassword()).isEqualTo("secret_pass_123");
        assertThat(search.getConnectionTimeout()).isEqualTo(8000);
        assertThat(search.getReadTimeout()).isEqualTo(45000);
        assertThat(search.getIndexPrefix()).isEqualTo("souklab_test_");
        assertThat(search.getSchemaManagement()).isEqualTo("validate");
        assertThat(search.isSyncOnStartup()).isFalse();
    }
}
