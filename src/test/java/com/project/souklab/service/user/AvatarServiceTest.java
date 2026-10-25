package com.project.souklab.service.user;

import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.AvatarProperties;
import com.project.souklab.dao.UserAvatarRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.user.AvatarResponseDTO;
import com.project.souklab.exception.AvatarLimitExceededException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.filestorage.exception.VirusDetectedException;
import com.project.souklab.filestorage.image.ImageProcessingService;
import com.project.souklab.filestorage.image.ImageVariant;
import com.project.souklab.filestorage.image.ResolutionTier;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.User;
import com.project.souklab.model.UserAvatar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AvatarService} verifying quota enforcement, pipeline sequencing,
 * multi-tier storage coordination, transactional profile synchronization, and compensating rollback deletions.
 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock
    private UserAvatarRepository userAvatarRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileValidator fileValidator;

    @Mock
    private VirusScanService virusScanService;

    @Mock
    private ImageProcessingService imageProcessingService;

    @Mock
    private StorageService storageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private Clock clock;
    private AvatarProperties avatarProperties;
    private AvatarService avatarService;
    private User testUser;
    private MockMultipartFile validFile;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneId.of("UTC"));
        avatarProperties = new AvatarProperties();
        avatarProperties.setMaxPerUser(10L);
        avatarProperties.setAllowedMimeTypes(List.of("image/jpeg", "image/png", "image/webp"));
        AppStorageProperties appProperties = new AppStorageProperties();
        appProperties.setFileServingPrefix("/api/v1/files/");
        avatarService = new AvatarService(
                userAvatarRepository,
                userRepository,
                fileValidator,
                virusScanService,
                imageProcessingService,
                storageService,
                transactionTemplate,
                clock,
                avatarProperties,
                appProperties,
                null
        );

        testUser = User.builder()
                .email("artisan@souklab.dz")
                .firstName("Karim")
                .lastName("B")
                .avatarUrl("/api/v1/files/old-avatar.jpg")
                .build();
        testUser.setId("user-uuid-123");

        validFile = new MockMultipartFile(
                "file",
                "portrait.png",
                "image/png",
                new byte[]{1, 2, 3, 4}
        );
    }

    /**
     * Helper to mock TransactionTemplate.execute by invoking the callback synchronously.
     */
    @SuppressWarnings("unchecked")
    private void mockTransactionTemplateSuccess() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
    }

    /**
     * Helper generating mock ImageVariants across all three tiers.
     */
    private Map<ResolutionTier, ImageVariant> createMockVariants() {
        Map<ResolutionTier, ImageVariant> map = new EnumMap<>(ResolutionTier.class);
        map.put(ResolutionTier.ORIGINAL, new ImageVariant(ResolutionTier.ORIGINAL, new byte[]{1, 1}, 1000, 1000, "image/png"));
        map.put(ResolutionTier.MEDIUM, new ImageVariant(ResolutionTier.MEDIUM, new byte[]{2, 2}, 500, 500, "image/png"));
        map.put(ResolutionTier.THUMBNAIL, new ImageVariant(ResolutionTier.THUMBNAIL, new byte[]{3, 3}, 150, 150, "image/png"));
        return map;
    }

    /**
     * Verifies the full happy path pipeline: quota check, validation, scanning, variant generation,
     * storage of all 3 tiers, prior active avatar deactivation, new avatar persistence, and User.avatarUrl update.
     */
    @Test
    @DisplayName("uploadAvatar succeeds and executes full pipeline in strict sequence")
    void uploadAvatar_whenValidInput_orchestratesFullPipelineAndActivatesAvatar() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(2L);

        ValidatedFile validatedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "portrait.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(InputStream.class), eq("portrait.png"), eq("image/png"), eq(validFile.getSize()), eq(avatarProperties.getAllowedMimeTypes())))
                .thenReturn(validatedFile);

        ValidatedFile scannedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "portrait.png", "image/png", validFile.getSize());
        when(virusScanService.scan(validatedFile)).thenReturn(scannedFile);

        when(imageProcessingService.generateVariants(scannedFile)).thenReturn(createMockVariants());

        StorageResult origResult = new StorageResult("key-orig-123.png", "portrait.png", "image/png", 2L, Instant.now(clock));
        StorageResult medResult = new StorageResult("key-med-123.png", "portrait.png", "image/png", 2L, Instant.now(clock));
        StorageResult thumbResult = new StorageResult("key-thumb-123.png", "portrait.png", "image/png", 2L, Instant.now(clock));
        when(storageService.store(any(InputStream.class), eq("portrait.png"), eq("image/png"), anyLong()))
                .thenReturn(origResult, medResult, thumbResult);

        UserAvatar priorActive = UserAvatar.builder()
                .user(testUser)
                .isActive(true)
                .storageKeyThumbnail("old-thumb.png")
                .build();
        priorActive.setId("old-avatar-id");
        when(userAvatarRepository.findByUserIdAndIsActiveTrue(testUser.getId())).thenReturn(Optional.of(priorActive));

        when(userAvatarRepository.save(any(UserAvatar.class))).thenAnswer(invocation -> {
            UserAvatar a = invocation.getArgument(0);
            if (a.getId() == null) {
                a.setId("new-avatar-id");
            }
            return a;
        });

        mockTransactionTemplateSuccess();

        AvatarResponseDTO response = avatarService.uploadAvatar(testUser, validFile);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("new-avatar-id");
        assertThat(response.getUrlOriginal()).isEqualTo("/api/v1/files/key-orig-123.png");
        assertThat(response.getUrlMedium()).isEqualTo("/api/v1/files/key-med-123.png");
        assertThat(response.getUrlThumbnail()).isEqualTo("/api/v1/files/key-thumb-123.png");
        assertThat(response.isActive()).isTrue();
        assertThat(response.getUploadedAt()).isEqualTo(LocalDateTime.now(clock));

        assertThat(priorActive.isActive()).isFalse();
        assertThat(testUser.getAvatarUrl()).isEqualTo("/api/v1/files/key-thumb-123.png");
        verify(userRepository).save(testUser);

        InOrder inOrder = inOrder(userAvatarRepository, fileValidator, virusScanService, imageProcessingService, storageService, transactionTemplate);
        inOrder.verify(userAvatarRepository).countByUserId(testUser.getId());
        inOrder.verify(fileValidator).validateAndSanitize(any(), eq("portrait.png"), eq("image/png"), eq(validFile.getSize()), eq(avatarProperties.getAllowedMimeTypes()));
        inOrder.verify(virusScanService).scan(validatedFile);
        inOrder.verify(imageProcessingService).generateVariants(scannedFile);
        inOrder.verify(storageService, times(3)).store(any(), any(), any(), anyLong());
        inOrder.verify(transactionTemplate).execute(any());
    }

    /**
     * Verifies that when a disk-buffered multipart file (backed by a real file stream on disk)
     * is processed, the stream is not prematurely closed between validation and image processing,
     * allowing image variant generation to read the full contents without an "IOException: Stream Closed".
     */
    @Test
    @DisplayName("uploadAvatar does not close disk-buffered file stream prematurely before image processing")
    void uploadAvatar_whenDiskBufferedMultipartFile_doesNotCloseStreamPrematurelyBeforeImageProcessing(@TempDir Path tempDir) throws Exception {
        Path tempFile = tempDir.resolve("disk-avatar.jpg");
        byte[] expectedBytes = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55};
        Files.write(tempFile, expectedBytes);

        MultipartFile diskMultipartFile = new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return "disk-avatar.jpg";
            }

            @Override
            public String getContentType() {
                return "image/jpeg";
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public long getSize() {
                return expectedBytes.length;
            }

            @Override
            public byte[] getBytes() throws IOException {
                return Files.readAllBytes(tempFile);
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return Files.newInputStream(tempFile);
            }

            @Override
            public void transferTo(File dest) throws IOException, IllegalStateException {
                Files.copy(tempFile, dest.toPath());
            }
        };

        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);

        when(fileValidator.validateAndSanitize(any(InputStream.class), eq("disk-avatar.jpg"), eq("image/jpeg"), eq((long) expectedBytes.length), any()))
                .thenAnswer(invocation -> new ValidatedFile(
                        invocation.getArgument(0),
                        "disk-avatar.jpg",
                        "image/jpeg",
                        expectedBytes.length
                ));

        when(virusScanService.scan(any(ValidatedFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(imageProcessingService.generateVariants(any(ValidatedFile.class))).thenAnswer(invocation -> {
            ValidatedFile vf = invocation.getArgument(0);
            byte[] consumed = vf.content().readAllBytes();
            assertThat(consumed).isEqualTo(expectedBytes);
            return createMockVariants();
        });

        StorageResult origResult = new StorageResult("key-orig-disk.jpg", "disk-avatar.jpg", "image/jpeg", 2L, Instant.now(clock));
        StorageResult medResult = new StorageResult("key-med-disk.jpg", "disk-avatar.jpg", "image/jpeg", 2L, Instant.now(clock));
        StorageResult thumbResult = new StorageResult("key-thumb-disk.jpg", "disk-avatar.jpg", "image/jpeg", 2L, Instant.now(clock));
        when(storageService.store(any(InputStream.class), eq("disk-avatar.jpg"), any(String.class), anyLong()))
                .thenReturn(origResult, medResult, thumbResult);

        when(userAvatarRepository.findByUserIdAndIsActiveTrue(testUser.getId())).thenReturn(Optional.empty());

        when(userAvatarRepository.save(any(UserAvatar.class))).thenAnswer(invocation -> {
            UserAvatar a = invocation.getArgument(0);
            a.setId("disk-avatar-id");
            return a;
        });

        mockTransactionTemplateSuccess();

        AvatarResponseDTO response = avatarService.uploadAvatar(testUser, diskMultipartFile);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("disk-avatar-id");
        assertThat(response.getUrlThumbnail()).isEqualTo("/api/v1/files/key-thumb-disk.jpg");
        verify(imageProcessingService).generateVariants(any(ValidatedFile.class));
    }

    /**
     * Verifies that when a user already has 10 avatars, upload is rejected with AvatarLimitExceededException
     * without calling any expensive downstream services.
     */
    @Test
    @DisplayName("uploadAvatar rejects upload with 409 AvatarLimitExceededException when count reaches 10")
    void uploadAvatar_whenQuotaExceeded_throwsAvatarLimitExceededExceptionWithoutCallingDownstreamServices() {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(10L);

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(AvatarLimitExceededException.class)
                .hasMessageContaining("Maximum avatar limit of 10 reached");

        verifyNoInteractions(fileValidator);
        verifyNoInteractions(virusScanService);
        verifyNoInteractions(imageProcessingService);
        verifyNoInteractions(storageService);
        verifyNoInteractions(transactionTemplate);
    }

    /**
     * Verifies that the configured maxPerUser property on AvatarProperties is respected.
     */
    @Test
    @DisplayName("uploadAvatar respects custom maxPerUser configured in AvatarProperties")
    void uploadAvatar_whenCustomQuotaExceeded_throwsAvatarLimitExceededException() {
        avatarProperties.setMaxPerUser(3L);
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(3L);

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(AvatarLimitExceededException.class)
                .hasMessageContaining("Maximum avatar limit of 3 reached");

        verifyNoInteractions(fileValidator);
    }

    /**
     * Verifies that when malware is detected, VirusDetectedException propagates and storageService is never invoked.
     */
    @Test
    @DisplayName("uploadAvatar propagates VirusDetectedException and never invokes StorageService")
    void uploadAvatar_whenVirusDetected_propagatesExceptionWithoutStoringFiles() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);

        ValidatedFile validatedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "eicar.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validatedFile);

        when(virusScanService.scan(validatedFile)).thenThrow(new VirusDetectedException("Eicar-Test-Signature"));

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(VirusDetectedException.class)
                .hasMessageContaining("Eicar-Test-Signature");

        verifyNoInteractions(imageProcessingService);
        verifyNoInteractions(storageService);
        verifyNoInteractions(transactionTemplate);
    }

    /**
     * Verifies rollback compensation when the second store call fails: the first stored key is deleted.
     */
    @Test
    @DisplayName("uploadAvatar triggers compensating delete for key 1 when store call 2 fails")
    void uploadAvatar_whenSecondStoreFails_executesCompensatingDeleteOnFirstKey() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);

        ValidatedFile validatedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "test.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validatedFile);
        when(virusScanService.scan(validatedFile)).thenReturn(validatedFile);
        when(imageProcessingService.generateVariants(validatedFile)).thenReturn(createMockVariants());

        when(storageService.store(any(), any(), any(), anyLong()))
                .thenReturn(new StorageResult("key-orig-only.png", "test.png", "image/png", 2L, Instant.now(clock)))
                .thenThrow(new StorageException("S3 connection timeout on medium tier"));

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("S3 connection timeout on medium tier");

        verify(storageService).delete("key-orig-only.png");
        verify(storageService, never()).delete("key-med.png");
        verifyNoInteractions(transactionTemplate);
    }

    /**
     * Verifies rollback compensation when the third store call fails: both the first and second keys are deleted.
     */
    @Test
    @DisplayName("uploadAvatar triggers compensating delete for keys 1 and 2 when store call 3 fails")
    void uploadAvatar_whenThirdStoreFails_executesCompensatingDeleteOnFirstTwoKeys() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);

        ValidatedFile validatedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "test.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validatedFile);
        when(virusScanService.scan(validatedFile)).thenReturn(validatedFile);
        when(imageProcessingService.generateVariants(validatedFile)).thenReturn(createMockVariants());

        when(storageService.store(any(), any(), any(), anyLong()))
                .thenReturn(new StorageResult("key-orig.png", "test.png", "image/png", 2L, Instant.now(clock)))
                .thenReturn(new StorageResult("key-med.png", "test.png", "image/png", 2L, Instant.now(clock)))
                .thenThrow(new StorageException("Disk full on thumbnail tier"));

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Disk full on thumbnail tier");

        verify(storageService).delete("key-orig.png");
        verify(storageService).delete("key-med.png");
        verifyNoInteractions(transactionTemplate);
    }

    /**
     * Verifies rollback compensation when the database transaction fails after all 3 stores succeeded:
     * all 3 stored keys are deleted to prevent orphaned S3 objects.
     */
    @Test
    @DisplayName("uploadAvatar triggers compensating delete for all 3 keys when database transaction fails")
    void uploadAvatar_whenDatabaseTransactionFails_executesCompensatingDeleteOnAllThreeKeys() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);

        ValidatedFile validatedFile = new ValidatedFile(new ByteArrayInputStream(validFile.getBytes()), "test.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validatedFile);
        when(virusScanService.scan(validatedFile)).thenReturn(validatedFile);
        when(imageProcessingService.generateVariants(validatedFile)).thenReturn(createMockVariants());

        when(storageService.store(any(), any(), any(), anyLong()))
                .thenReturn(new StorageResult("k1.png", "test.png", "image/png", 2L, Instant.now(clock)))
                .thenReturn(new StorageResult("k2.png", "test.png", "image/png", 2L, Instant.now(clock)))
                .thenReturn(new StorageResult("k3.png", "test.png", "image/png", 2L, Instant.now(clock)));

        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("Database deadlock"));

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database deadlock");

        verify(storageService).delete("k1.png");
        verify(storageService).delete("k2.png");
        verify(storageService).delete("k3.png");
    }

    @Test
    void uploadAvatar_continuesCompensationWhenOneStorageDeleteFails() throws Exception {
        when(userRepository.findWithLockById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);
        ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(new byte[]{1}),
                "portrait.png", "image/png", 1);
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any()))
                .thenReturn(validated);
        when(virusScanService.scan(validated)).thenReturn(validated);
        when(imageProcessingService.generateVariants(validated)).thenReturn(createMockVariants());
        when(storageService.store(any(), any(), any(), anyLong()))
                .thenReturn(new StorageResult("first", "portrait.png", "image/png", 2, Instant.now()))
                .thenReturn(new StorageResult("second", "portrait.png", "image/png", 2, Instant.now()))
                .thenReturn(new StorageResult("third", "portrait.png", "image/png", 2, Instant.now()));
        when(transactionTemplate.execute(any())).thenThrow(new IllegalStateException("db failure"));
        doThrow(new StorageException("delete failed")).when(storageService).delete("second");

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(IllegalStateException.class);
        verify(storageService).delete("first");
        verify(storageService).delete("second");
        verify(storageService).delete("third");
    }

    @Test
    void compatibilityConstructorAndCurrentUserEntryPointsDelegateToOwnedOperations() {
        AvatarService compatibilityService = new AvatarService(
                userAvatarRepository, userRepository, fileValidator, virusScanService,
                imageProcessingService, storageService, transactionTemplate, clock, avatarProperties);
        assertThat(compatibilityService).isNotNull();

        AppStorageProperties properties = new AppStorageProperties();
        properties.setFileServingPrefix("/api/v1/files/");
        AvatarService currentUserService = new AvatarService(
                userAvatarRepository, userRepository, fileValidator, virusScanService,
                imageProcessingService, storageService, transactionTemplate, clock,
                avatarProperties, properties, currentUserProvider);
        when(currentUserProvider.requireCurrentUser()).thenReturn(testUser);
        when(userAvatarRepository.findByUserId(testUser.getId(), Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(currentUserService.listAvatars(Pageable.unpaged()).getContent()).isEmpty();

        assertThatThrownBy(() -> currentUserService.uploadAvatar(null))
                .isInstanceOf(BadRequestException.class);
        when(userAvatarRepository.findByIdAndUserId("missing", testUser.getId()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> currentUserService.deleteAvatar("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> currentUserService.activateAvatar("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Verifies that a missing or empty file throws BadRequestException immediately.
     */
    @Test
    @DisplayName("uploadAvatar rejects empty or null files with BadRequestException")
    void uploadAvatar_whenFileEmptyOrNull_throwsBadRequestException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, null))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, emptyFile))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userAvatarRepository);
        verifyNoInteractions(fileValidator);
    }

    @Test
    @DisplayName("uploadAvatar rejects a null current user before resolving repositories")
    void uploadAvatar_whenCurrentUserIsNull_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> avatarService.uploadAvatar(null, validFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Current user cannot be null");
        verifyNoInteractions(userRepository, userAvatarRepository);
    }

    @Test
    @DisplayName("uploadAvatar translates an unreadable multipart stream to StorageException")
    void uploadAvatar_whenMultipartStreamCannotBeRead_throwsStorageException() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);
        MultipartFile unreadable = new MockMultipartFile("file", "broken.png", "image/png", "bytes".getBytes()) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("stream unavailable");
            }
        };

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, unreadable))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to read uploaded avatar stream");
        verifyNoInteractions(virusScanService, imageProcessingService, storageService, transactionTemplate);
    }

    @Test
    @DisplayName("uploadAvatar rejects incomplete image variant maps")
    void uploadAvatar_whenRequiredVariantIsMissing_throwsStorageException() throws Exception {
        when(userAvatarRepository.countByUserId(testUser.getId())).thenReturn(0L);
        ValidatedFile validated = new ValidatedFile(
                new ByteArrayInputStream(validFile.getBytes()), "portrait.png", "image/png", validFile.getSize());
        when(fileValidator.validateAndSanitize(any(InputStream.class), any(), any(), anyLong(), any()))
                .thenReturn(validated);
        when(virusScanService.scan(validated)).thenReturn(validated);
        EnumMap<ResolutionTier, ImageVariant> incomplete = new EnumMap<>(ResolutionTier.class);
        incomplete.put(ResolutionTier.ORIGINAL, new ImageVariant(
                ResolutionTier.ORIGINAL, new byte[]{1}, 1, 1, "image/png"));
        incomplete.put(ResolutionTier.MEDIUM, new ImageVariant(
                ResolutionTier.MEDIUM, new byte[]{1}, 1, 1, "image/png"));
        when(imageProcessingService.generateVariants(validated)).thenReturn(incomplete);

        assertThatThrownBy(() -> avatarService.uploadAvatar(testUser, validFile))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("all required resolution tiers");
        verifyNoInteractions(storageService, transactionTemplate);
    }

    private UserAvatar buildMockAvatar(String id, boolean isActive) {
        UserAvatar avatar = UserAvatar.builder()
                .user(testUser)
                .storageKeyOriginal("orig-" + id + ".png")
                .storageKeyMedium("med-" + id + ".png")
                .storageKeyThumbnail("thumb-" + id + ".png")
                .originalFilename("avatar-" + id + ".png")
                .contentType("image/png")
                .fileSize(2048L)
                .isActive(isActive)
                .uploadedAt(LocalDateTime.now(clock))
                .build();
        avatar.setId(id);
        return avatar;
    }

    @Nested
    @DisplayName("listAvatars Tests")
    class ListAvatarsTests {

        @Test
        @DisplayName("listAvatars returns mapped PaginatedResponse scoped to calling user")
        void listAvatars_returnsMappedPaginatedResponse_forCallingUser() {
            UserAvatar avatar1 = buildMockAvatar("av-1", true);
            UserAvatar avatar2 = buildMockAvatar("av-2", false);

            Pageable pageable = PageRequest.of(0, 20);
            Page<UserAvatar> page = new PageImpl<>(List.of(avatar1, avatar2), pageable, 2);

            when(userAvatarRepository.findByUserId(testUser.getId(), pageable)).thenReturn(page);

            PaginatedResponse<AvatarResponseDTO> response = avatarService.listAvatars(testUser, pageable);

            assertThat(response).isNotNull();
            assertThat(response.getContent()).hasSize(2);
            assertThat(response.getTotalElements()).isEqualTo(2);
            assertThat(response.getContent().get(0).getId()).isEqualTo("av-1");
            assertThat(response.getContent().get(0).getUrlThumbnail()).isEqualTo("/api/v1/files/thumb-av-1.png");
            assertThat(response.getContent().get(0).isActive()).isTrue();
            assertThat(response.getContent().get(1).getId()).isEqualTo("av-2");
            assertThat(response.getContent().get(1).isActive()).isFalse();

            verify(userAvatarRepository).findByUserId(testUser.getId(), pageable);
        }

        @Test
        @DisplayName("listAvatars throws IllegalArgumentException when currentUser is null")
        void listAvatars_whenUserIsNull_throwsIllegalArgumentException() {
            Pageable pageable = PageRequest.of(0, 20);
            assertThatThrownBy(() -> avatarService.listAvatars(null, pageable))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current user cannot be null");
        }
    }

    @Nested
    @DisplayName("deleteAvatar Tests")
    class DeleteAvatarTests {

        @Test
        @DisplayName("deleteAvatar of inactive avatar removes entity and 3 S3 keys without modifying user avatarUrl")
        void deleteAvatar_whenInactive_deletesEntityAndS3Keys_leavesUserAvatarUrlUnchanged() {
            UserAvatar avatar = buildMockAvatar("av-inactive", false);
            testUser.setAvatarUrl("/api/v1/files/thumb-other.png");

            when(userAvatarRepository.findByIdAndUserId("av-inactive", testUser.getId()))
                    .thenReturn(Optional.of(avatar));
            mockTransactionTemplateSuccess();

            avatarService.deleteAvatar(testUser, "av-inactive");

            verify(userAvatarRepository).delete(avatar);
            verify(userRepository, never()).save(any());
            assertThat(testUser.getAvatarUrl()).isEqualTo("/api/v1/files/thumb-other.png");

            verify(storageService).delete("orig-av-inactive.png");
            verify(storageService).delete("med-av-inactive.png");
            verify(storageService).delete("thumb-av-inactive.png");
        }

        @Test
        @DisplayName("deleteAvatar of active avatar removes entity, clears user avatarUrl to null, and deletes 3 S3 keys")
        void deleteAvatar_whenActive_deletesEntityAndS3Keys_clearsUserAvatarUrlToNull() {
            UserAvatar avatar = buildMockAvatar("av-active", true);
            testUser.setAvatarUrl("/api/v1/files/thumb-av-active.png");

            when(userAvatarRepository.findByIdAndUserId("av-active", testUser.getId()))
                    .thenReturn(Optional.of(avatar));
            mockTransactionTemplateSuccess();

            avatarService.deleteAvatar(testUser, "av-active");

            verify(userAvatarRepository).delete(avatar);
            verify(userRepository).save(testUser);
            assertThat(testUser.getAvatarUrl()).isNull();
            verify(userAvatarRepository, never()).findByUserIdAndIsActiveTrue(any());

            verify(storageService).delete("orig-av-active.png");
            verify(storageService).delete("med-av-active.png");
            verify(storageService).delete("thumb-av-active.png");
        }

        @Test
        @DisplayName("deleteAvatar catches and logs storageService.delete exception without rethrowing")
        void deleteAvatar_whenStorageDeleteThrows_doesNotRethrowAndDBAlreadyDeleted() {
            UserAvatar avatar = buildMockAvatar("av-error", false);

            when(userAvatarRepository.findByIdAndUserId("av-error", testUser.getId()))
                    .thenReturn(Optional.of(avatar));
            mockTransactionTemplateSuccess();

            doThrow(new StorageException("S3 connection timeout"))
                    .when(storageService).delete("med-av-error.png");

            avatarService.deleteAvatar(testUser, "av-error");

            verify(userAvatarRepository).delete(avatar);
            verify(storageService).delete("orig-av-error.png");
            verify(storageService).delete("med-av-error.png");
            verify(storageService).delete("thumb-av-error.png");
        }

        @Test
        @DisplayName("deleteAvatar throws ResourceNotFoundException when avatar not found or not owned")
        void deleteAvatar_whenNotFoundOrNotOwned_throwsResourceNotFoundException() {
            when(userAvatarRepository.findByIdAndUserId("missing-id", testUser.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> avatarService.deleteAvatar(testUser, "missing-id"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Avatar not found with id: missing-id");

            verify(userAvatarRepository, never()).delete(any());
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("deleteAvatar throws IllegalArgumentException when currentUser is null")
        void deleteAvatar_whenUserIsNull_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> avatarService.deleteAvatar(null, "av-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current user cannot be null");
        }
    }

    @Nested
    @DisplayName("activateAvatar Tests")
    class ActivateAvatarTests {

        @Test
        @DisplayName("activateAvatar of inactive avatar deactivates previous, activates target, and syncs user avatarUrl")
        void activateAvatar_whenInactive_deactivatesPrevious_activatesTarget_andSyncsUserUrl() {
            UserAvatar targetAvatar = buildMockAvatar("av-target", false);
            UserAvatar previousActive = buildMockAvatar("av-prev", true);

            when(userAvatarRepository.findByIdAndUserId("av-target", testUser.getId()))
                    .thenReturn(Optional.of(targetAvatar));
            when(userAvatarRepository.findByUserIdAndIsActiveTrue(testUser.getId()))
                    .thenReturn(Optional.of(previousActive));
            when(userAvatarRepository.save(any(UserAvatar.class))).thenAnswer(invocation -> invocation.getArgument(0));
            mockTransactionTemplateSuccess();

            AvatarResponseDTO response = avatarService.activateAvatar(testUser, "av-target");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("av-target");
            assertThat(response.isActive()).isTrue();

            assertThat(previousActive.isActive()).isFalse();
            verify(userAvatarRepository).save(previousActive);

            assertThat(targetAvatar.isActive()).isTrue();
            verify(userAvatarRepository).save(targetAvatar);

            assertThat(testUser.getAvatarUrl()).isEqualTo("/api/v1/files/thumb-av-target.png");
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("activateAvatar of already active avatar is an idempotent no-op 200 without DB writes")
        void activateAvatar_whenAlreadyActive_isIdempotentNoOp200() {
            UserAvatar targetAvatar = buildMockAvatar("av-already-active", true);

            when(userAvatarRepository.findByIdAndUserId("av-already-active", testUser.getId()))
                    .thenReturn(Optional.of(targetAvatar));

            AvatarResponseDTO response = avatarService.activateAvatar(testUser, "av-already-active");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("av-already-active");
            assertThat(response.isActive()).isTrue();

            verifyNoInteractions(transactionTemplate);
            verify(userAvatarRepository, never()).findByUserIdAndIsActiveTrue(any());
            verify(userAvatarRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("activateAvatar throws ResourceNotFoundException when avatar not found or not owned")
        void activateAvatar_whenNotFoundOrNotOwned_throwsResourceNotFoundException() {
            when(userAvatarRepository.findByIdAndUserId("foreign-id", testUser.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> avatarService.activateAvatar(testUser, "foreign-id"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Avatar not found with id: foreign-id");

            verify(userAvatarRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("activateAvatar throws IllegalArgumentException when currentUser is null")
        void activateAvatar_whenUserIsNull_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> avatarService.activateAvatar(null, "av-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current user cannot be null");
        }
    }
}
