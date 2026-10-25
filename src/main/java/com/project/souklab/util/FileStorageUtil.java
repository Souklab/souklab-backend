package com.project.souklab.util;

import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class FileStorageUtil {
    private final Path uploadDir;

    public FileStorageUtil(AppStorageProperties appStorageProperties) {
        this.uploadDir = Path.of(appStorageProperties.getUploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException ex) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot initialize file storage");
        }
    }

    public String storePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Uploaded file is required");
        }
        String rawName = file.getOriginalFilename();
        String safeName = "file.pdf";
        if (rawName != null && !rawName.isBlank()) {
            safeName = Path.of(rawName).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        String fileName = UUID.randomUUID() + "-" + safeName;
        Path target = uploadDir.resolve(fileName).normalize();
        if (!target.startsWith(uploadDir)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException ex) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store uploaded file");
        }
    }

    public Path resolve(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "File path is required");
        }
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        if (!target.startsWith(uploadDir)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        return target;
    }

    public boolean exists(String filePath) {
        return Files.exists(resolve(filePath));
    }

    public void deleteIfExists(String filePath) {
        try {
            Files.deleteIfExists(resolve(filePath));
        } catch (IOException ex) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to delete stored file");
        }
    }
}
