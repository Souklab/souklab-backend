package com.project.souklab.service.artisan;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.security.Permission;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanGalleryImageRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.filestorage.exception.FileTooLargeException;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.lifecycle.StorageObjectLifecycle;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArtisanGalleryService}.
 * Verifies showcase upload validation, quota limits (max 20 images), sequential reordering,
 * soft-delete lifecycle operations, and storage rollback compensation on database failure.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanGalleryServiceTest {

    private static final String ARTISAN_EMAIL = "artisan@souklab.com";
    private static final String ARTISAN_ID = "artisan-uuid-123";

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private ArtisanGalleryImageRepository galleryImageRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private FileValidator fileValidator;

    @Mock
    private VirusScanService virusScanService;

    @Mock
    private StorageObjectLifecycle storageObjectLifecycle;

    @Mock
    private FileUrlResolver fileUrlResolver;

    @Spy
    private ArtisanProperties artisanProperties = new ArtisanProperties();

    @Spy
    private AppStorageProperties appStorageProperties = new AppStorageProperties();

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private ArtisanGalleryService galleryService;

    private Artisan testArtisan;
    private User testUser;

    @BeforeEach
    void setUp() {
        appStorageProperties.setFileServingPrefix("/api/v1/files/");
        artisanProperties.getGallery().setMaxImages(20);
        artisanProperties.getGallery().setMaxFileSize(DataSize.ofMegabytes(10));
        artisanProperties.getGallery().setAllowedMimeTypes(List.of("image/jpeg", "image/png"));
        lenient().when(fileUrlResolver.toStorageKey(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).replace("/api/v1/files/", ""));
        testUser = User.builder()
                .email(ARTISAN_EMAIL)
                .build();
        testUser.setId(ARTISAN_ID);

        testArtisan = Artisan.builder()
                .id(ARTISAN_ID)
                .user(testUser)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Helper configuring authenticated artisan security context.
     */
    private void authenticateArtisan() {
        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        Authentication authentication = Mockito.mock(Authentication.class);

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ARTISAN_EMAIL);

        GrantedAuthority authority = Permission.Artisan.CONTENT;
        doReturn(List.of(authority)).when(authentication).getAuthorities();

        SecurityContextHolder.setContext(securityContext);
        when(artisanRepository.findByUserEmailIgnoreCase(ARTISAN_EMAIL)).thenReturn(Optional.of(testArtisan));
    }

    @Nested
    @DisplayName("Upload Showcase Image Tests")
    class UploadImageTests {

        /**
         * Verifies successful upload, antivirus scan, storage persistence, and entity creation.
         */
        @Test
        @DisplayName("uploadImage_whenValidPayload_shouldStoreAndReturnDto")
        void uploadImage_whenValidPayload_shouldStoreAndReturnDto() throws Exception {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile(
                    "file", "ceramic_vase.jpg", "image/jpeg", "image payload content".getBytes()
            );

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(2L);

            ValidatedFile validatedFile = new ValidatedFile(
                    new ByteArrayInputStream(file.getBytes()), "ceramic_vase.jpg", "image/jpeg", file.getSize()
            );
            when(fileValidator.validateAndSanitize(any(InputStream.class), eq("ceramic_vase.jpg"), eq("image/jpeg"), eq(file.getSize()), anyList()))
                    .thenReturn(validatedFile);

            when(virusScanService.scan(validatedFile)).thenReturn(validatedFile);

            ArtisanGalleryImage existing0 = ArtisanGalleryImage.builder().displayOrder(0).build();
            ArtisanGalleryImage existing1 = ArtisanGalleryImage.builder().displayOrder(1).build();
            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(List.of(existing0, existing1));

            StorageResult storageResult = new StorageResult("storage-key-abc", "ceramic_vase.jpg", "image/jpeg", file.getSize(), Instant.now());
            when(storageService.store(any(InputStream.class), eq("ceramic_vase.jpg"), eq("image/jpeg"), eq(file.getSize())))
                    .thenReturn(storageResult);

            ArtisanGalleryImage savedImage = ArtisanGalleryImage.builder()
                    .artisan(testArtisan)
                    .imageUrl("/api/v1/files/storage-key-abc")
                    .title("Glazed Ceramic Vase")
                    .caption("Hand-thrown pottery from Tizi Ouzou")
                    .displayOrder(2)
                    .build();
            savedImage.setId("img-1");

            when(galleryImageRepository.save(any(ArtisanGalleryImage.class))).thenReturn(savedImage);

            GalleryImageResponseDTO response = galleryService.uploadImage(file, "Glazed Ceramic Vase", "Hand-thrown pottery from Tizi Ouzou");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("img-1");
            assertThat(response.getImageUrl()).isEqualTo("/api/v1/files/storage-key-abc");
            assertThat(response.getTitle()).isEqualTo("Glazed Ceramic Vase");
            assertThat(response.getCaption()).isEqualTo("Hand-thrown pottery from Tizi Ouzou");
            assertThat(response.getDisplayOrder()).isEqualTo(2);

            verify(storageService).store(any(InputStream.class), eq("ceramic_vase.jpg"), eq("image/jpeg"), eq(file.getSize()));
            verify(galleryImageRepository).save(any(ArtisanGalleryImage.class));
            verify(storageService, never()).delete(anyString());
        }

        /**
         * Verifies quota rejection when artisan already has 20 active gallery images.
         */
        @Test
        @DisplayName("uploadImage_whenQuotaExceeded_shouldThrowBadRequestException")
        void uploadImage_whenQuotaExceeded_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile(
                    "file", "photo.jpg", "image/jpeg", "content".getBytes()
            );

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(20L);

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Gallery quota exceeded. Maximum 20 images allowed.");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
            verify(galleryImageRepository, never()).save(any());
        }

        /**
         * Verifies rejection when upload file is null or empty.
         */
        @Test
        @DisplayName("uploadImage_whenFileEmpty_shouldThrowBadRequestException")
        void uploadImage_whenFileEmpty_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.jpg", "image/jpeg", new byte[0]
            );

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> galleryService.uploadImage(emptyFile, "Title", "Caption"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Image file is required and cannot be empty.");

            assertThatThrownBy(() -> galleryService.uploadImage(null, "Title", "Caption"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Image file is required and cannot be empty.");
        }

        /**
         * Verifies rejection when upload file exceeds 10MB limit.
         */
        @Test
        @DisplayName("uploadImage_whenFileSizeExceeds10Mb_shouldThrowFileTooLargeException")
        void uploadImage_whenFileSizeExceeds10Mb_shouldThrowFileTooLargeException() {
            authenticateArtisan();

            long oversized = (10L * 1024 * 1024) + 1024;
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file", "large.jpg", "image/jpeg", new byte[10]
            ) {
                @Override
                public long getSize() {
                    return oversized;
                }
            };

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> galleryService.uploadImage(largeFile, "Title", "Caption"))
                    .isInstanceOf(FileTooLargeException.class);

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        /**
         * Verifies gallery upload honors custom configured image quota.
         */
        @Test
        @DisplayName("uploadImage_whenCustomQuotaConfigured_shouldRespectCustomQuota")
        void uploadImage_whenCustomQuotaConfigured_shouldRespectCustomQuota() {
            authenticateArtisan();
            artisanProperties.getGallery().setMaxImages(5);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "photo.jpg", "image/jpeg", "content".getBytes()
            );

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(5L);

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Gallery quota exceeded. Maximum 5 images allowed.");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
            verify(galleryImageRepository, never()).save(any());
        }

        /**
         * Verifies gallery upload honors custom configured max file size.
         */
        @Test
        @DisplayName("uploadImage_whenCustomMaxFileSizeConfigured_shouldRespectCustomLimit")
        void uploadImage_whenCustomMaxFileSizeConfigured_shouldRespectCustomLimit() {
            authenticateArtisan();
            artisanProperties.getGallery().setMaxFileSize(DataSize.ofMegabytes(2));

            long oversized = (2L * 1024 * 1024) + 1024;
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file", "large.jpg", "image/jpeg", new byte[10]
            ) {
                @Override
                public long getSize() {
                    return oversized;
                }
            };

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> galleryService.uploadImage(largeFile, "Title", "Caption"))
                    .isInstanceOf(FileTooLargeException.class);

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        /**
         * Verifies compensating storage deletion when entity persistence fails.
         */
        @Test
        @DisplayName("uploadImage_whenDatabaseSaveFails_shouldRollbackStorageDeletion")
        void uploadImage_whenDatabaseSaveFails_shouldRollbackStorageDeletion() throws Exception {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.jpg", "image/jpeg", "bytes".getBytes()
            );

            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "test.jpg", "image/jpeg", file.getSize());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);
            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID)).thenReturn(Collections.emptyList());

            StorageResult storageResult = new StorageResult("storage-key-to-delete", "test.jpg", "image/jpeg", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);

            when(galleryImageRepository.save(any(ArtisanGalleryImage.class)))
                    .thenThrow(new RuntimeException("Database connection timeout"));

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Database connection timeout");

            verify(storageService).delete("storage-key-to-delete");
        }

        @Test
        @DisplayName("uploadImage_whenInputStreamCannotBeRead_shouldThrowStorageException")
        void uploadImage_whenInputStreamCannotBeRead_shouldThrowStorageException() throws Exception {
            authenticateArtisan();
            MockMultipartFile unreadableFile = new MockMultipartFile(
                    "file", "broken.jpg", "image/jpeg", "bytes".getBytes()) {
                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("stream unavailable");
                }
            };
            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> galleryService.uploadImage(unreadableFile, "Title", "Caption"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to read uploaded image stream");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("uploadImage_whenCompensatingDeleteFails_shouldPreserveOriginalFailure")
        void uploadImage_whenCompensatingDeleteFails_shouldPreserveOriginalFailure() throws Exception {
            authenticateArtisan();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.jpg", "image/jpeg", "bytes".getBytes());
            when(galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);
            ValidatedFile validated = new ValidatedFile(
                    new ByteArrayInputStream(file.getBytes()), "test.jpg", "image/jpeg", file.getSize());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList()))
                    .thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);
            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(Collections.emptyList());
            when(storageService.store(any(), anyString(), anyString(), anyLong()))
                    .thenReturn(new StorageResult("delete-fails-key", "test.jpg", "image/jpeg", file.getSize(), Instant.now()));
            when(galleryImageRepository.save(any(ArtisanGalleryImage.class)))
                    .thenThrow(new RuntimeException("database failure"));
            doThrow(new RuntimeException("delete failure")).when(storageService).delete("delete-fails-key");

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("database failure");
            verify(storageService).delete("delete-fails-key");
        }

        /**
         * Verifies unauthenticated invocation throws UnauthorizedException.
         */
        @Test
        @DisplayName("uploadImage_whenUnauthenticated_shouldThrowUnauthorizedException")
        void uploadImage_whenUnauthenticated_shouldThrowUnauthorizedException() {
            SecurityContextHolder.clearContext();

            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "bytes".getBytes());

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(UnauthorizedException.class);
        }

        /**
         * Verifies authenticated non-artisan role throws ForbiddenException.
         */
        @Test
        @DisplayName("uploadImage_whenNonArtisanRole_shouldThrowForbiddenException")
        void uploadImage_whenNonArtisanRole_shouldThrowForbiddenException() {
            SecurityContext securityContext = Mockito.mock(SecurityContext.class);
            Authentication authentication = Mockito.mock(Authentication.class);

            when(securityContext.getAuthentication()).thenReturn(authentication);
            when(authentication.isAuthenticated()).thenReturn(true);

            GrantedAuthority clientAuthority = Permission.Profile.READ;
            doReturn(List.of(clientAuthority)).when(authentication).getAuthorities();

            SecurityContextHolder.setContext(securityContext);

            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "bytes".getBytes());

            assertThatThrownBy(() -> galleryService.uploadImage(file, "Title", "Caption"))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied: " + Permission.Artisan.CONTENT.value() + " permission required.");
        }
    }

    @Nested
    @DisplayName("Get Artisan Gallery Tests")
    class GetMyGalleryTests {

        /**
         * Verifies retrieval of active gallery images in ascending display order.
         */
        @Test
        @DisplayName("getMyGallery_whenActiveImagesExist_shouldReturnSortedList")
        void getMyGallery_whenActiveImagesExist_shouldReturnSortedList() {
            authenticateArtisan();

            ArtisanGalleryImage img1 = ArtisanGalleryImage.builder().imageUrl("/api/v1/files/k1").title("T1").displayOrder(0).build();
            img1.setId("id-1");
            ArtisanGalleryImage img2 = ArtisanGalleryImage.builder().imageUrl("/api/v1/files/k2").title("T2").displayOrder(1).build();
            img2.setId("id-2");

            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(List.of(img1, img2));

            List<GalleryImageResponseDTO> result = galleryService.getMyGallery();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo("id-1");
            assertThat(result.get(0).getDisplayOrder()).isEqualTo(0);
            assertThat(result.get(1).getId()).isEqualTo("id-2");
            assertThat(result.get(1).getDisplayOrder()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Reorder Gallery Tests")
    class ReorderGalleryTests {

        /**
         * Verifies updating sequential display orders across active images.
         */
        @Test
        @DisplayName("reorderGallery_whenValidOrderProvided_shouldUpdateDisplayOrdersAndSave")
        void reorderGallery_whenValidOrderProvided_shouldUpdateDisplayOrdersAndSave() {
            authenticateArtisan();

            ArtisanGalleryImage img1 = ArtisanGalleryImage.builder().displayOrder(0).build();
            img1.setId("id-1");
            ArtisanGalleryImage img2 = ArtisanGalleryImage.builder().displayOrder(1).build();
            img2.setId("id-2");
            ArtisanGalleryImage img3 = ArtisanGalleryImage.builder().displayOrder(2).build();
            img3.setId("id-3");

            List<ArtisanGalleryImage> activeImages = new ArrayList<>(List.of(img1, img2, img3));
            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(activeImages);

            List<String> reorderedIds = List.of("id-3", "id-1", "id-2");

            galleryService.reorderGallery(reorderedIds);

            assertThat(img3.getDisplayOrder()).isEqualTo(0);
            assertThat(img1.getDisplayOrder()).isEqualTo(1);
            assertThat(img2.getDisplayOrder()).isEqualTo(2);

            verify(galleryImageRepository).saveAll(activeImages);
        }

        /**
         * Verifies rejection when list is null.
         */
        @Test
        @DisplayName("reorderGallery_whenNullList_shouldThrowBadRequestException")
        void reorderGallery_whenNullList_shouldThrowBadRequestException() {
            authenticateArtisan();

            assertThatThrownBy(() -> galleryService.reorderGallery(null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Image IDs list cannot be null.");
        }

        /**
         * Verifies rejection when list contains fewer or more IDs than active gallery count.
         */
        @Test
        @DisplayName("reorderGallery_whenCountMismatch_shouldThrowBadRequestException")
        void reorderGallery_whenCountMismatch_shouldThrowBadRequestException() {
            authenticateArtisan();

            ArtisanGalleryImage img1 = ArtisanGalleryImage.builder().displayOrder(0).build();
            img1.setId("id-1");
            ArtisanGalleryImage img2 = ArtisanGalleryImage.builder().displayOrder(1).build();
            img2.setId("id-2");

            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(List.of(img1, img2));

            List<String> incomplete = List.of("id-1");

            assertThatThrownBy(() -> galleryService.reorderGallery(incomplete))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Reorder list count does not match current active gallery image count.");
        }

        /**
         * Verifies rejection when list contains duplicates.
         */
        @Test
        @DisplayName("reorderGallery_whenDuplicateIds_shouldThrowBadRequestException")
        void reorderGallery_whenDuplicateIds_shouldThrowBadRequestException() {
            authenticateArtisan();

            ArtisanGalleryImage img1 = ArtisanGalleryImage.builder().displayOrder(0).build();
            img1.setId("id-1");
            ArtisanGalleryImage img2 = ArtisanGalleryImage.builder().displayOrder(1).build();
            img2.setId("id-2");

            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(List.of(img1, img2));

            List<String> duplicates = List.of("id-1", "id-1");

            assertThatThrownBy(() -> galleryService.reorderGallery(duplicates))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Reorder list contains duplicate image IDs.");
        }

        /**
         * Verifies rejection when list contains an unknown or foreign image ID.
         */
        @Test
        @DisplayName("reorderGallery_whenUnknownId_shouldThrowBadRequestException")
        void reorderGallery_whenUnknownId_shouldThrowBadRequestException() {
            authenticateArtisan();

            ArtisanGalleryImage img1 = ArtisanGalleryImage.builder().displayOrder(0).build();
            img1.setId("id-1");

            when(galleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(ARTISAN_ID))
                    .thenReturn(List.of(img1));

            List<String> foreign = List.of("foreign-id-999");

            assertThatThrownBy(() -> galleryService.reorderGallery(foreign))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Reorder list does not match artisan's active gallery image IDs.");
        }
    }

    @Nested
    @DisplayName("Delete Image Tests")
    class DeleteImageTests {

        /**
         * Verifies soft deletion timestamp is set on existing owned gallery image.
         */
        @Test
        @DisplayName("deleteImage_whenImageExistsAndOwned_shouldSetDeletedAtAndSave")
        void deleteImage_whenImageExistsAndOwned_shouldSetDeletedAtAndSave() {
            authenticateArtisan();

            ArtisanGalleryImage image = ArtisanGalleryImage.builder()
                    .artisan(testArtisan)
                    .imageUrl("/api/v1/files/k1")
                    .displayOrder(0)
                    .build();
            image.setId("img-to-delete");

            when(galleryImageRepository.findByIdAndArtisanIdAndDeletedAtIsNull("img-to-delete", ARTISAN_ID))
                    .thenReturn(Optional.of(image));

            galleryService.deleteImage("img-to-delete");

            assertThat(image.getDeletedAt()).isNotNull();
            verify(galleryImageRepository).save(image);
        }

        /**
         * Verifies ResourceNotFoundException when image is not found or owned by another user.
         */
        @Test
        @DisplayName("deleteImage_whenImageNotFound_shouldThrowResourceNotFoundException")
        void deleteImage_whenImageNotFound_shouldThrowResourceNotFoundException() {
            authenticateArtisan();

            when(galleryImageRepository.findByIdAndArtisanIdAndDeletedAtIsNull("non-existent-img", ARTISAN_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> galleryService.deleteImage("non-existent-img"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Gallery image not found.");

            verify(galleryImageRepository, never()).save(any());
        }
    }
}
