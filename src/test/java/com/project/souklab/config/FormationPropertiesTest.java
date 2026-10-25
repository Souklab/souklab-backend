package com.project.souklab.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.unit.DataSize;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated unit tests verifying FormationProperties property binding
 * and environment variable resolution.
 */
class FormationPropertiesTest {

    @Test
    @DisplayName("FormationProperties does not provide policy defaults upon instantiation")
    void policyValuesAreNotHardcodedUponInstantiation() {
        FormationProperties config = new FormationProperties();

        assertThat(config.getThumbnail()).isNotNull();
        assertThat(config.getThumbnail().getMaxFileSize()).isNull();
        assertThat(config.getThumbnail().getAllowedMimeTypes()).isNull();

        assertThat(config.getFile()).isNotNull();
        assertThat(config.getFile().getMaxCount()).isZero();
        assertThat(config.getFile().getMaxFileSize()).isNull();
        assertThat(config.getFile().getAllowedMimeTypes()).isNull();

        assertThat(config.getCancellation()).isNotNull();
        assertThat(config.getCancellation().getDeadlineHours()).isZero();

        assertThat(config.getPagination()).isNotNull();
        assertThat(config.getPagination().getDefaultPageSize()).isZero();

        assertThat(config.getDefaultCurrency()).isNull();
    }

    @Test
    @DisplayName("FormationProperties correctly binds custom configuration values from property sources")
    void bindsCustomConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-formation", Map.of(
                "app.formation.thumbnail.max-file-size", "15MB",
                "app.formation.thumbnail.allowed-mime-types", "image/png,image/webp",
                "app.formation.file.max-count", "20",
                "app.formation.file.max-file-size", "50MB",
                "app.formation.file.allowed-mime-types", "application/pdf",
                "app.formation.cancellation.deadline-hours", "48",
                "app.formation.pagination.default-page-size", "25",
                "app.formation.default-currency", "EUR"
        )));

        Binder binder = new Binder(ConfigurationPropertySources.from(environment.getPropertySources()));
        FormationProperties config = binder.bind("app.formation", FormationProperties.class).get();

        assertThat(config.getThumbnail().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(15));
        assertThat(config.getThumbnail().getAllowedMimeTypes()).containsExactly("image/png", "image/webp");
        assertThat(config.getFile().getMaxCount()).isEqualTo(20);
        assertThat(config.getFile().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
        assertThat(config.getFile().getAllowedMimeTypes()).containsExactly("application/pdf");
        assertThat(config.getCancellation().getDeadlineHours()).isEqualTo(48);
        assertThat(config.getPagination().getDefaultPageSize()).isEqualTo(25);
        assertThat(config.getDefaultCurrency()).isEqualTo("EUR");
    }
}
