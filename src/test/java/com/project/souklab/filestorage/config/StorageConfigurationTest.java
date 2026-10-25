package com.project.souklab.filestorage.config;

import java.util.List;

import com.project.souklab.filestorage.image.ImageProcessingService;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.scan.VirusScanner;
import com.project.souklab.filestorage.stub.InMemoryStorageService;
import com.project.souklab.filestorage.validation.FileValidator;
import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageConfigurationTest {

    private final StorageConfiguration configuration = new StorageConfiguration();
    private final Clock clock = Clock.systemUTC();

    @Test
    void createsProviderIndependentAndInMemoryBeans() {
        StorageProperties properties = validValidationProperties();

        assertThat(configuration.tika()).isInstanceOf(Tika.class);
        assertThat(configuration.fileValidator(properties, new Tika())).isInstanceOf(FileValidator.class);
        assertThat(configuration.inMemoryStorageService(clock)).isInstanceOf(InMemoryStorageService.class);
        assertThat(configuration.imageProcessingService()).isInstanceOf(ImageProcessingService.class);
        assertThat(configuration.virusScanner(properties)).isInstanceOf(VirusScanner.class);
        assertThat(configuration.virusScanService(properties, configuration.virusScanner(properties)))
                .isInstanceOf(VirusScanService.class);
    }

    @Test
    void validatesFileValidatorConfiguration() {
        StorageProperties properties = validValidationProperties();
        assertThatThrownBy(() -> configuration.fileValidator(null, new Tika()))
                .hasMessageContaining("max-file-size");
        properties.getValidation().setMaxFileSize(null);
        assertThatThrownBy(() -> configuration.fileValidator(properties, new Tika()))
                .hasMessageContaining("max-file-size");
        StorageProperties missingMimeTypes = validValidationProperties();
        missingMimeTypes.getValidation().setAllowedMimeTypes(null);
        assertThatThrownBy(() -> configuration.fileValidator(missingMimeTypes, new Tika()))
                .hasMessageContaining("allowed-mime-types");
        StorageProperties missingValidation = validValidationProperties();
        missingValidation.setValidation(null);
        assertThatThrownBy(() -> configuration.fileValidator(missingValidation, new Tika()))
                .hasMessageContaining("max-file-size");
        StorageProperties emptyMimeTypes = validValidationProperties();
        emptyMimeTypes.getValidation().setAllowedMimeTypes(List.of());
        assertThatThrownBy(() -> configuration.fileValidator(emptyMimeTypes, new Tika()))
                .hasMessageContaining("allowed-mime-types");
    }

    @Test
    void validatesAndBuildsS3ClientConfiguration() {
        StorageProperties properties = validS3Properties();
        try (S3Client client = configuration.s3Client(properties)) {
            assertThat(client).isNotNull();
        }

        StorageProperties missingRegion = validS3Properties();
        missingRegion.getS3().setRegion(null);
        assertThatThrownBy(() -> configuration.s3Client(missingRegion)).hasMessageContaining("region");
        StorageProperties blankRegion = validS3Properties();
        blankRegion.getS3().setRegion(" ");
        assertThatThrownBy(() -> configuration.s3Client(blankRegion)).hasMessageContaining("region");
        StorageProperties missingBucket = validS3Properties();
        missingBucket.getS3().setBucket(" ");
        assertThatThrownBy(() -> configuration.s3Client(missingBucket)).hasMessageContaining("bucket");
        StorageProperties missingAccessKey = validS3Properties();
        missingAccessKey.getS3().setAccessKey(" ");
        assertThatThrownBy(() -> configuration.s3Client(missingAccessKey)).hasMessageContaining("access-key");
        StorageProperties nullAccessKey = validS3Properties();
        nullAccessKey.getS3().setAccessKey(null);
        assertThatThrownBy(() -> configuration.s3Client(nullAccessKey)).hasMessageContaining("access-key");
        StorageProperties missingSecretKey = validS3Properties();
        missingSecretKey.getS3().setSecretKey(null);
        assertThatThrownBy(() -> configuration.s3Client(missingSecretKey)).hasMessageContaining("secret-key");
        StorageProperties blankSecretKey = validS3Properties();
        blankSecretKey.getS3().setSecretKey(" ");
        assertThatThrownBy(() -> configuration.s3Client(blankSecretKey)).hasMessageContaining("secret-key");
        StorageProperties missingS3 = validS3Properties();
        missingS3.setS3(null);
        assertThatThrownBy(() -> configuration.s3Client(missingS3)).hasMessageContaining("access-key");
        StorageProperties noEndpointOrPathStyle = validS3Properties();
        noEndpointOrPathStyle.getS3().setEndpoint(" ");
        noEndpointOrPathStyle.getS3().setPathStyleAccess(false);
        try (S3Client client = configuration.s3Client(noEndpointOrPathStyle)) {
            assertThat(client).isNotNull();
        }
        StorageProperties nullEndpoint = validS3Properties();
        nullEndpoint.getS3().setEndpoint(null);
        try (S3Client client = configuration.s3Client(nullEndpoint)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void validatesEnabledVirusScanConfiguration() {
        StorageProperties properties = validValidationProperties();
        properties.getVirusScan().setEnabled(true);
        properties.getVirusScan().setHost("clamav");
        properties.getVirusScan().setPort(3310);
        assertThat(configuration.virusScanner(properties)).isNotNull();

        properties.getVirusScan().setHost(" ");
        assertThatThrownBy(() -> configuration.virusScanner(properties)).hasMessageContaining("host");
        properties.getVirusScan().setHost("clamav");
        properties.getVirusScan().setPort(0);
        assertThatThrownBy(() -> configuration.virusScanner(properties)).hasMessageContaining("port");
        StorageProperties disabled = validValidationProperties();
        disabled.setVirusScan(null);
        assertThat(configuration.virusScanner(disabled)).isNotNull();
    }

    private StorageProperties validValidationProperties() {
        StorageProperties properties = new StorageProperties();
        properties.getValidation().setMaxFileSize(DataSize.ofMegabytes(1));
        properties.getValidation().setAllowedMimeTypes(List.of("image/jpeg"));
        return properties;
    }

    private StorageProperties validS3Properties() {
        StorageProperties properties = validValidationProperties();
        properties.getS3().setAccessKey("access");
        properties.getS3().setSecretKey("secret");
        properties.getS3().setBucket("bucket");
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setEndpoint("http://localhost:9000");
        properties.getS3().setPathStyleAccess(true);
        return properties;
    }
}
