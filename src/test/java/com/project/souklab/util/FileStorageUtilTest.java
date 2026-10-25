package com.project.souklab.util;

import java.io.IOException;
import java.io.InputStream;

import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageUtilTest {
    @TempDir Path tempDir;

    @Test
    void storesChecksAndDeletesPdfFiles() throws Exception {
        AppStorageProperties properties = new AppStorageProperties();
        properties.setUploadDir(tempDir.toString());
        FileStorageUtil storage = new FileStorageUtil(properties);
        String path = storage.storePdf(new MockMultipartFile("file", "document.pdf", "application/pdf", "pdf".getBytes()));

        assertThat(storage.exists(path)).isTrue();
        assertThat(Files.readString(storage.resolve(path))).isEqualTo("pdf");
        storage.deleteIfExists(path);
        storage.deleteIfExists(path);
        assertThat(storage.exists(path)).isFalse();
    }

    @Test
    void rejectsMissingFilesAndWrapsReadFailures() {
        AppStorageProperties properties = new AppStorageProperties();
        properties.setUploadDir(tempDir.toString());
        FileStorageUtil storage = new FileStorageUtil(properties);
        assertThatThrownBy(() -> storage.storePdf(null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> storage.storePdf(new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(AppException.class);
        var broken = new MockMultipartFile("file", "file.pdf", "application/pdf", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException {
                throw new IOException("read failure");
            }
        };
        assertThatThrownBy(() -> storage.storePdf(broken)).isInstanceOf(AppException.class);
    }

    @Test
    void rejectsUnusableUploadDirectoryAndWrapsDeleteFailures() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "occupied");
        AppStorageProperties invalidProperties = new AppStorageProperties();
        invalidProperties.setUploadDir(file.toString());
        assertThatThrownBy(() -> new FileStorageUtil(invalidProperties))
                .isInstanceOf(AppException.class);

        Path nonEmptyDirectory = Files.createDirectory(tempDir.resolve("non-empty"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "content");
        AppStorageProperties validProperties = new AppStorageProperties();
        validProperties.setUploadDir(tempDir.toString());
        FileStorageUtil storage = new FileStorageUtil(validProperties);
        assertThatThrownBy(() -> storage.deleteIfExists(nonEmptyDirectory.toString()))
                .isInstanceOf(AppException.class);
    }
}
