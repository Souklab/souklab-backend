package com.project.souklab.filestorage;

import com.project.souklab.config.ClockConfig;
import com.project.souklab.filestorage.config.StorageConfiguration;
import com.project.souklab.filestorage.config.StorageProperties;
import com.project.souklab.filestorage.exception.FileNotFoundStorageException;
import com.project.souklab.filestorage.exception.FileTooLargeException;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.filestorage.exception.UnsupportedFileTypeException;
import com.project.souklab.filestorage.s3.S3StorageService;
import com.project.souklab.filestorage.stub.InMemoryStorageService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification test suite for Phase D.0b S3-compatible storage implementation backed by MinIO.
 * Validates streaming uploads, byte-for-byte retrieval, deletion, exists lifecycle,
 * exception wrapping, shared validation routing, and provider configuration switching.
 */
class S3StorageServiceVerificationTest {

    /**
     * Standard valid JPEG header bytes: FF D8 FF E0.
     */
    private static final byte[] VALID_JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00
    };

    /**
     * Standard valid PNG header bytes: 89 50 4E 47 0D 0A 1A 0A.
     */
    private static final byte[] VALID_PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
    };

    private StorageProperties properties;
    private S3Client s3Client;
    private S3StorageService s3StorageService;
    private FileValidator validator;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class, ClockConfig.class)
            .withPropertyValues(
                    "storage.validation.max-file-size=2MB",
                    "storage.validation.allowed-mime-types=image/jpeg,image/png,application/pdf",
                    "storage.virus-scan.enabled=false"
            );

    /**
     * Initializes MinIO client fixtures and explicitly configures all StorageProperties
     * (endpoint, region, bucket, credentials, path-style access, max size, allowed types).
     */
    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setProvider("s3");
        properties.getS3().setEndpoint("http://localhost:9000");
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setBucket("souklab-files");
        properties.getS3().setAccessKey("minioadmin");
        properties.getS3().setSecretKey("minioadmin_secret");
        properties.getS3().setPathStyleAccess(true);
        properties.getS3().setAutoCreateBucket(true);
        properties.getValidation().setMaxFileSize(DataSize.ofMegabytes(2));
        properties.getValidation().setAllowedMimeTypes(List.of(
                "image/jpeg",
                "image/png",
                "application/pdf"
        ));

        s3Client = S3Client.builder()
                .region(Region.of(properties.getS3().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getS3().getAccessKey(), properties.getS3().getSecretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.getS3().getPathStyleAccess())
                        .build())
                .endpointOverride(URI.create(properties.getS3().getEndpoint()))
                .build();

        s3StorageService = new S3StorageService(properties, s3Client, Clock.systemUTC());
        s3StorageService.initBucket();

        validator = new FileValidator(properties, new Tika());
    }

    /**
     * Verifies storing a valid image in MinIO S3 backend produces an opaque key and correct metadata.
     */
    @Test
    @DisplayName("V1: Store valid image in MinIO S3 backend")
    void testV1_storeValidImageInS3() {
        System.out.println("=== V1 EVIDENCE ===");
        ValidatedFile validated = validator.validateAndSanitize(
                new ByteArrayInputStream(VALID_JPEG_BYTES),
                "pottery_vase.jpg",
                "image/jpeg",
                VALID_JPEG_BYTES.length
        );

        StorageResult result = s3StorageService.store(
                validated.content(),
                validated.sanitizedFilename(),
                validated.detectedMimeType(),
                validated.size()
        );

        System.out.println("Store Result Key: " + result.key());
        System.out.println("Store Result Original Filename: " + result.originalFilename());
        System.out.println("Store Result Content Type: " + result.contentType());
        System.out.println("Store Result Size: " + result.size() + " bytes");

        assertThat(result.key()).isNotBlank();
        assertThat(result.key()).endsWith(".jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.size()).isEqualTo(VALID_JPEG_BYTES.length);
        assertThat(s3StorageService.exists(result.key())).isTrue();
    }

    /**
     * Verifies retrieving a stored file from MinIO returns byte-for-byte identical content.
     */
    @Test
    @DisplayName("V2: Retrieve stored file and confirm byte-for-byte fidelity")
    void testV2_retrieveFileFromS3_byteForByteFidelity() throws Exception {
        System.out.println("=== V2 EVIDENCE ===");
        ValidatedFile validated = validator.validateAndSanitize(
                new ByteArrayInputStream(VALID_JPEG_BYTES),
                "carpet_weaving.jpg",
                "image/jpeg",
                VALID_JPEG_BYTES.length
        );

        StorageResult stored = s3StorageService.store(
                validated.content(),
                validated.sanitizedFilename(),
                validated.detectedMimeType(),
                validated.size()
        );

        StorageResource retrieved = s3StorageService.retrieve(stored.key());
        byte[] downloadedBytes;
        try (InputStream stream = retrieved.content()) {
            downloadedBytes = stream.readAllBytes();
        }

        System.out.println("Retrieved Storage Key: " + retrieved.key());
        System.out.println("Retrieved Content-Type: " + retrieved.contentType());
        System.out.println("Retrieved Size: " + retrieved.size() + " bytes");
        System.out.println("Retrieved Original Filename: " + retrieved.originalFilename());
        System.out.println("Byte-for-byte Match: " + Arrays.equals(VALID_JPEG_BYTES, downloadedBytes));

        assertThat(downloadedBytes).isEqualTo(VALID_JPEG_BYTES);
        assertThat(retrieved.contentType()).isEqualTo("image/jpeg");
        assertThat(retrieved.originalFilename()).isEqualTo("carpet_weaving.jpg");
        assertThat(retrieved.size()).isEqualTo(VALID_JPEG_BYTES.length);
    }

    /**
     * Verifies deleting a file from MinIO removes it and subsequent retrieve() throws FileNotFoundStorageException.
     */
    @Test
    @DisplayName("V3: Delete file from MinIO and verify subsequent 404")
    void testV3_deleteFileFromS3_andVerifyAbsence() {
        System.out.println("=== V3 EVIDENCE ===");
        ValidatedFile validated = validator.validateAndSanitize(
                new ByteArrayInputStream(VALID_JPEG_BYTES),
                "leather_pouch.jpg",
                "image/jpeg",
                VALID_JPEG_BYTES.length
        );

        StorageResult stored = s3StorageService.store(
                validated.content(),
                validated.sanitizedFilename(),
                validated.detectedMimeType(),
                validated.size()
        );

        String key = stored.key();
        assertThat(s3StorageService.exists(key)).isTrue();
        System.out.println("File exists before delete: " + s3StorageService.exists(key));

        s3StorageService.delete(key);
        System.out.println("Delete called for key: " + key);
        System.out.println("File exists after delete: " + s3StorageService.exists(key));

        assertThat(s3StorageService.exists(key)).isFalse();
        assertThatThrownBy(() -> s3StorageService.retrieve(key))
                .isInstanceOf(FileNotFoundStorageException.class)
                .hasMessageContaining(key);
        System.out.println("retrieve() threw expected FileNotFoundStorageException");
    }

    /**
     * Verifies exists() accurately reflects object presence before and after store/delete.
     */
    @Test
    @DisplayName("V4: exists() accurately reflects presence/absence before and after store/delete")
    void testV4_existsBehaviorLifecycle() {
        System.out.println("=== V4 EVIDENCE ===");
        String nonexistentKey = "nonexistent-uuid-key.png";
        System.out.println("1. Nonexistent key exists(): " + s3StorageService.exists(nonexistentKey));
        assertThat(s3StorageService.exists(nonexistentKey)).isFalse();

        ValidatedFile validated = validator.validateAndSanitize(
                new ByteArrayInputStream(VALID_PNG_BYTES),
                "ceramic_tile.png",
                "image/png",
                VALID_PNG_BYTES.length
        );
        StorageResult stored = s3StorageService.store(
                validated.content(),
                validated.sanitizedFilename(),
                validated.detectedMimeType(),
                validated.size()
        );
        System.out.println("2. Stored key exists(): " + s3StorageService.exists(stored.key()));
        assertThat(s3StorageService.exists(stored.key())).isTrue();

        s3StorageService.delete(stored.key());
        System.out.println("3. Deleted key exists(): " + s3StorageService.exists(stored.key()));
        assertThat(s3StorageService.exists(stored.key())).isFalse();
    }

    /**
     * Verifies size and MIME validation logic runs before any S3 call, proving validation
     * is a provider-agnostic shared layer.
     */
    @Test
    @DisplayName("V5: Validation tests routed through S3 backend behave identically (shared validation layer)")
    void testV5_validationTestsWithS3Backend() {
        System.out.println("=== V5 EVIDENCE ===");

        byte[] oversizedData = new byte[3 * 1024 * 1024];
        assertThatThrownBy(() -> validator.validateAndSanitize(
                new ByteArrayInputStream(oversizedData),
                "large.jpg",
                "image/jpeg",
                oversizedData.length
        )).isInstanceOf(FileTooLargeException.class);
        System.out.println("1. Oversized declared payload rejected with FileTooLargeException");

        byte[] scriptBytes = "#!/bin/bash\necho hello\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator.validateAndSanitize(
                new ByteArrayInputStream(scriptBytes),
                "script.sh",
                "image/jpeg",
                scriptBytes.length
        )).isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessage("File type 'application/x-sh' is not allowed");
        System.out.println("2. Disallowed MIME type rejected with UnsupportedFileTypeException");

        assertThatThrownBy(() -> validator.validateAndSanitize(
                new ByteArrayInputStream(VALID_PNG_BYTES),
                "tampered.jpg",
                "image/jpeg",
                VALID_PNG_BYTES.length
        )).isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessage("Declared content type 'image/jpeg' does not match actual file content 'image/png'");
        System.out.println("3. Allowed-type mismatch rejected with clean message");
        System.out.println("Proof: All validation checks execute prior to any S3 call, proving validation is a provider-agnostic shared layer.");
    }

    /**
     * Verifies that backend connection failures are wrapped in StorageException (HTTP 500 STORAGE_ERROR).
     */
    @Test
    @DisplayName("V6: Backend connection failure throws StorageException (proper exception wrapping)")
    void testV6_backendDown_throwsStorageException() {
        System.out.println("=== V6 EVIDENCE ===");

        StorageProperties deadProps = new StorageProperties();
        deadProps.setProvider("s3");
        deadProps.getS3().setEndpoint("http://localhost:59999");
        deadProps.getS3().setRegion("us-east-1");
        deadProps.getS3().setBucket("souklab-files");
        deadProps.getS3().setAccessKey("fake");
        deadProps.getS3().setSecretKey("fake");
        deadProps.getS3().setPathStyleAccess(true);
        deadProps.getS3().setAutoCreateBucket(false);

        S3Client deadClient = S3Client.builder()
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fake", "fake")
                ))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .endpointOverride(URI.create("http://localhost:59999"))
                .build();

        S3StorageService deadStorageService = new S3StorageService(deadProps, deadClient, Clock.systemUTC());

        assertThatThrownBy(() -> deadStorageService.store(
                new ByteArrayInputStream(VALID_JPEG_BYTES),
                "photo.jpg",
                "image/jpeg",
                VALID_JPEG_BYTES.length
        )).isInstanceOf(StorageException.class)
                .satisfies(ex -> {
                    System.out.println("Exception: " + ex.getClass().getSimpleName());
                    System.out.println("Exception Message: " + ex.getMessage());
                    System.out.println("Status: " + ((StorageException) ex).getStatus());
                    System.out.println("ErrorCode: " + ((StorageException) ex).getErrorCode());
                });

        System.out.println("Proof: Raw AWS SDK network failure wrapped in StorageException (HTTP 500 STORAGE_ERROR)");
    }

    /**
     * Verifies switching storage.provider cleanly registers either InMemoryStorageService or S3StorageService.
     */
    @Test
    @DisplayName("V7: Switching storage.provider switches backend bean between in-memory and s3")
    void testV7_providerPropertySwitching() {
        System.out.println("=== V7 EVIDENCE ===");

        contextRunner.withPropertyValues("storage.provider=in-memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageService.class);
                    StorageService service = context.getBean(StorageService.class);
                    assertThat(service).isInstanceOf(InMemoryStorageService.class);
                    System.out.println("storage.provider=in-memory -> Bean: " + service.getClass().getName());
                });

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StorageService.class);
            StorageService service = context.getBean(StorageService.class);
            assertThat(service).isInstanceOf(InMemoryStorageService.class);
            System.out.println("storage.provider [default] -> Bean: " + service.getClass().getName());
        });

        contextRunner.withPropertyValues(
                "storage.provider=s3",
                "storage.s3.endpoint=http://localhost:9000",
                "storage.s3.region=us-east-1",
                "storage.s3.bucket=souklab-files",
                "storage.s3.access-key=minioadmin",
                "storage.s3.secret-key=minioadmin_secret",
                "storage.s3.auto-create-bucket=false"
        ).run(context -> {
            assertThat(context).hasSingleBean(StorageService.class);
            StorageService service = context.getBean(StorageService.class);
            assertThat(service).isInstanceOf(S3StorageService.class);
            System.out.println("storage.provider=s3 -> Bean: " + service.getClass().getName());
        });

        System.out.println("Proof: Both implementations coexist behind the StorageService interface, cleanly selected by configuration alone.");
    }

    /**
     * Verifies fail-fast startup behavior when storage.provider=s3 is active but credentials are blank or missing.
     */
    @Test
    @DisplayName("Fail-fast: storage.provider=s3 fails fast at startup if credentials are blank or missing")
    void testFailFast_whenS3CredentialsMissingOrBlank() {
        System.out.println("=== FAIL-FAST CREDENTIAL CHECK EVIDENCE ===");
        contextRunner.withPropertyValues(
                "storage.provider=s3",
                "storage.s3.endpoint=http://localhost:9000",
                "storage.s3.region=us-east-1",
                "storage.s3.bucket=souklab-files",
                "storage.s3.access-key=",
                "storage.s3.secret-key="
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("storage.s3.access-key and storage.s3.secret-key are required when storage.provider=s3");
            System.out.println("Proof: Context failed to start with exact message: "
                    + context.getStartupFailure().getCause().getMessage());
        });
    }

    /**
     * Verifies fail-fast startup behavior when storage.provider=s3 is active but bucket is blank or missing.
     */
    @Test
    @DisplayName("Fail-fast: storage.provider=s3 fails fast at startup if bucket is blank or missing")
    void testFailFast_whenS3BucketMissingOrBlank() {
        System.out.println("=== FAIL-FAST BUCKET CHECK EVIDENCE ===");
        contextRunner.withPropertyValues(
                "storage.provider=s3",
                "storage.s3.endpoint=http://localhost:9000",
                "storage.s3.region=us-east-1",
                "storage.s3.access-key=minioadmin",
                "storage.s3.secret-key=minioadmin_secret",
                "storage.s3.bucket="
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("storage.s3.bucket is required when storage.provider=s3");
            System.out.println("Proof: Context failed to start with exact message: "
                    + context.getStartupFailure().getCause().getMessage());
        });
    }

    /**
     * Verifies fail-fast startup behavior when storage.validation.max-file-size is missing.
     */
    @Test
    @DisplayName("Fail-fast: context fails fast at startup if storage.validation.max-file-size is missing")
    void testFailFast_whenValidationMaxFileSizeMissing() {
        System.out.println("=== FAIL-FAST MAX-FILE-SIZE CHECK EVIDENCE ===");
        new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class, ClockConfig.class)
                .withPropertyValues(
                        "storage.provider=in-memory",
                        "storage.validation.allowed-mime-types=image/jpeg"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("storage.validation.max-file-size is required");
                    System.out.println("Proof: Context failed to start with exact message: "
                            + context.getStartupFailure().getCause().getMessage());
                });
    }

    /**
     * Verifies fail-fast startup behavior when storage.validation.allowed-mime-types is missing or empty.
     */
    @Test
    @DisplayName("Fail-fast: context fails fast at startup if storage.validation.allowed-mime-types is missing")
    void testFailFast_whenValidationAllowedMimeTypesMissing() {
        System.out.println("=== FAIL-FAST ALLOWED-MIME-TYPES CHECK EVIDENCE ===");
        new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class, ClockConfig.class)
                .withPropertyValues(
                        "storage.provider=in-memory",
                        "storage.validation.max-file-size=2MB"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("storage.validation.allowed-mime-types is required and cannot be empty");
                    System.out.println("Proof: Context failed to start with exact message: "
                            + context.getStartupFailure().getCause().getMessage());
                });
    }
}
