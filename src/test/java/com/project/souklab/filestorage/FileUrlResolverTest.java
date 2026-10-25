package com.project.souklab.filestorage;

import com.project.souklab.config.AppStorageProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileUrlResolverTest {
    @Test
    void convertsKeysAndRejectsUnsafeOrUnknownUrls() {
        AppStorageProperties properties = new AppStorageProperties();
        properties.setFileServingPrefix("/files");
        FileUrlResolver resolver = new FileUrlResolver(properties);

        assertThat(resolver.getPrefix()).isEqualTo("/files/");
        assertThat(resolver.toUrl("avatar.png")).isEqualTo("/files/avatar.png");
        assertThat(resolver.toStorageKey("/files/avatar.png")).isEqualTo("avatar.png");
        assertThat(resolver.toStorageKey(null)).isNull();
        assertThat(resolver.toStorageKey(" ")).isNull();
        assertThat(resolver.toStorageKey("/other/avatar.png")).isNull();
        assertThat(resolver.toStorageKey("/files/")).isNull();
        assertThat(resolver.toStorageKey("/files/path/avatar.png")).isNull();
        assertThat(resolver.toUrl(null)).isNull();
    }
}
