package com.project.souklab.service.artisan;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.security.Permission;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanCertificationRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dto.artisan.CertificationResponseDTO;
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
import com.project.souklab.model.ArtisanCertification;
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
import java.time.LocalDate;
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
 * Unit tests for {@link ArtisanCertificationService}.
 * Verifies certification upload validation (PDF and images), quota limits (max 10 records),
 * default verification flag (isVerified=false), soft-deletion, and storage rollback on persistence error.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanCertificationServiceTest {

    private static final String ARTISAN_EMAIL = "artisan@souklab.com";
    private static final String ARTISAN_ID = "artisan-uuid-456";

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private ArtisanCertificationRepository certificationRepository;

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
    private ArtisanCertificationService certificationService;

    private Artisan testArtisan;
    private User testUser;

    @BeforeEach
    void setUp() {
        appStorageProperties.setFileServingPrefix("/api/v1/files/");
        artisanProperties.getCertification().setMaxCount(10);
        artisanProperties.getCertification().setMaxFileSize(DataSize.ofMegabytes(10));
        artisanProperties.getCertification().setAllowedMimeTypes(List.of("application/pdf", "image/jpeg", "image/png"));
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
    @DisplayName("Upload Certification Tests")
    class UploadCertificationTests {

        /**
         * Verifies uploading a valid PDF document defaults to isVerified=false and constructs the correct document URL.
         */
        @Test
        @DisplayName("uploadCertification_whenValidPdf_shouldStoreAndReturnDtoWithIsVerifiedFalse")
        void uploadCertification_whenValidPdf_shouldStoreAndReturnDtoWithIsVerifiedFalse() throws Exception {
            authenticateArtisan();

            MockMultipartFile pdfFile = new MockMultipartFile(
                    "file", "artisan_card.pdf", "application/pdf", "%PDF-1.4 mock content".getBytes()
            );

            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(1L);

            ValidatedFile validated = new ValidatedFile(
                    new ByteArrayInputStream(pdfFile.getBytes()), "artisan_card.pdf", "application/pdf", pdfFile.getSize()
            );
            when(fileValidator.validateAndSanitize(any(InputStream.class), eq("artisan_card.pdf"), eq("application/pdf"), eq(pdfFile.getSize()), anyList()))
                    .thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("cert-key-pdf", "artisan_card.pdf", "application/pdf", pdfFile.getSize(), Instant.now());
            when(storageService.store(any(InputStream.class), eq("artisan_card.pdf"), eq("application/pdf"), eq(pdfFile.getSize())))
                    .thenReturn(storageResult);

            LocalDate issued = LocalDate.of(2024, 6, 1);
            LocalDate expires = LocalDate.of(2029, 6, 1);

            ArtisanCertification savedEntity = ArtisanCertification.builder()
                    .artisan(testArtisan)
                    .title("Carte d'Artisan Professionnel")
                    .issuer("Chambre de l'Artisanat et des Metiers de Tizi Ouzou")
                    .issuedAt(issued)
                    .expiresAt(expires)
                    .documentUrl("/api/v1/files/cert-key-pdf")
                    .isVerified(false)
                    .build();
            savedEntity.setId("cert-1");

            when(certificationRepository.save(any(ArtisanCertification.class))).thenReturn(savedEntity);

            CertificationResponseDTO response = certificationService.uploadCertification(
                    pdfFile, "Carte d'Artisan Professionnel", "Chambre de l'Artisanat et des Metiers de Tizi Ouzou", issued, expires
            );

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("cert-1");
            assertThat(response.getTitle()).isEqualTo("Carte d'Artisan Professionnel");
            assertThat(response.getIssuer()).isEqualTo("Chambre de l'Artisanat et des Metiers de Tizi Ouzou");
            assertThat(response.getDocumentUrl()).isEqualTo("/api/v1/files/cert-key-pdf");
            assertThat(response.isVerified()).isFalse();
            assertThat(response.getIssuedAt()).isEqualTo(issued);
            assertThat(response.getExpiresAt()).isEqualTo(expires);

            verify(storageService).store(any(InputStream.class), eq("artisan_card.pdf"), eq("application/pdf"), eq(pdfFile.getSize()));
            verify(certificationRepository).save(any(ArtisanCertification.class));
            verify(storageService, never()).delete(anyString());
        }

        /**
         * Verifies uploading a valid image document (JPEG/PNG) succeeds.
         */
        @Test
        @DisplayName("uploadCertification_whenValidImage_shouldStoreAndReturnDto")
        void uploadCertification_whenValidImage_shouldStoreAndReturnDto() throws Exception {
            authenticateArtisan();

            MockMultipartFile imageFile = new MockMultipartFile(
                    "file", "diploma.png", "image/png", "png content".getBytes()
            );

            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            ValidatedFile validated = new ValidatedFile(
                    new ByteArrayInputStream(imageFile.getBytes()), "diploma.png", "image/png", imageFile.getSize()
            );
            when(fileValidator.validateAndSanitize(any(), eq("diploma.png"), eq("image/png"), eq(imageFile.getSize()), anyList()))
                    .thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("cert-key-png", "diploma.png", "image/png", imageFile.getSize(), Instant.now());
            when(storageService.store(any(), eq("diploma.png"), eq("image/png"), eq(imageFile.getSize())))
                    .thenReturn(storageResult);

            ArtisanCertification savedEntity = ArtisanCertification.builder()
                    .artisan(testArtisan)
                    .title("Diplome de Maitre Artisan")
                    .issuer("Ministere du Tourisme")
                    .documentUrl("/api/v1/files/cert-key-png")
                    .isVerified(false)
                    .build();
            savedEntity.setId("cert-2");

            when(certificationRepository.save(any(ArtisanCertification.class))).thenReturn(savedEntity);

            CertificationResponseDTO response = certificationService.uploadCertification(
                    imageFile, "Diplome de Maitre Artisan", "Ministere du Tourisme", null, null
            );

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("cert-2");
            assertThat(response.isVerified()).isFalse();
        }

        /**
         * Verifies quota rejection when artisan already has 10 active certifications.
         */
        @Test
        @DisplayName("uploadCertification_whenQuotaExceeded_shouldThrowBadRequestException")
        void uploadCertification_whenQuotaExceeded_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(10L);

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification quota exceeded. Maximum 10 records allowed.");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        /**
         * Verifies rejection when file is missing or empty.
         */
        @Test
        @DisplayName("uploadCertification_whenFileEmpty_shouldThrowBadRequestException")
        void uploadCertification_whenFileEmpty_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(emptyFile, "Title", "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification document file is required and cannot be empty.");
        }

        /**
         * Verifies rejection when title or issuer is blank.
         */
        @Test
        @DisplayName("uploadCertification_whenTitleOrIssuerBlank_shouldThrowBadRequestException")
        void uploadCertification_whenTitleOrIssuerBlank_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "", "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification title is required.");

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "  ", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification issuer is required.");

            assertThatThrownBy(() -> certificationService.uploadCertification(file, null, "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification title is required.");

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification issuer is required.");
        }

        @Test
        void uploadCertification_whenFileIsNull_shouldThrowBadRequestException() {
            authenticateArtisan();
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(null, "Title", "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("document file is required");
        }

        /**
         * Verifies rejection when expiration date precedes issuance date.
         */
        @Test
        @DisplayName("uploadCertification_whenExpiresBeforeIssued_shouldThrowBadRequestException")
        void uploadCertification_whenExpiresBeforeIssued_shouldThrowBadRequestException() {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            LocalDate issued = LocalDate.of(2025, 1, 1);
            LocalDate expires = LocalDate.of(2024, 1, 1);

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", issued, expires))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Expiration date cannot be earlier than issuance date.");
        }

        /**
         * Verifies rejection when file size exceeds 15MB.
         */
        @Test
        @DisplayName("uploadCertification_whenFileSizeExceeds15Mb_shouldThrowFileTooLargeException")
        void uploadCertification_whenFileSizeExceeds15Mb_shouldThrowFileTooLargeException() {
            authenticateArtisan();

            long oversized = (15L * 1024 * 1024) + 1024;
            MockMultipartFile largeFile = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[10]) {
                @Override
                public long getSize() {
                    return oversized;
                }
            };

            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(largeFile, "Title", "Issuer", null, null))
                    .isInstanceOf(FileTooLargeException.class);
        }

        /**
         * Verifies certification upload honors custom configured quota.
         */
        @Test
        @DisplayName("uploadCertification_whenCustomQuotaConfigured_shouldRespectCustomQuota")
        void uploadCertification_whenCustomQuotaConfigured_shouldRespectCustomQuota() {
            authenticateArtisan();
            artisanProperties.getCertification().setMaxCount(3);

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(3L);

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Certification quota exceeded. Maximum 3 records allowed.");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        /**
         * Verifies certification upload honors custom configured max file size.
         */
        @Test
        @DisplayName("uploadCertification_whenCustomMaxFileSizeConfigured_shouldRespectCustomLimit")
        void uploadCertification_whenCustomMaxFileSizeConfigured_shouldRespectCustomLimit() {
            authenticateArtisan();
            artisanProperties.getCertification().setMaxFileSize(DataSize.ofMegabytes(5));

            long oversized = (5L * 1024 * 1024) + 1024;
            MockMultipartFile largeFile = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[10]) {
                @Override
                public long getSize() {
                    return oversized;
                }
            };

            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(largeFile, "Title", "Issuer", null, null))
                    .isInstanceOf(FileTooLargeException.class);
        }

        /**
         * Verifies compensating storage deletion when entity persistence throws an exception.
         */
        @Test
        @DisplayName("uploadCertification_whenDatabaseSaveFails_shouldRollbackStorageDeletion")
        void uploadCertification_whenDatabaseSaveFails_shouldRollbackStorageDeletion() throws Exception {
            authenticateArtisan();

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "cert.pdf", "application/pdf", file.getSize());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("rollback-cert-key", "cert.pdf", "application/pdf", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);

            when(certificationRepository.save(any(ArtisanCertification.class)))
                    .thenThrow(new RuntimeException("Database error"));

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Database error");

            verify(storageService).delete("rollback-cert-key");
        }

        @Test
        @DisplayName("uploadCertification_whenInputStreamCannotBeRead_shouldThrowStorageException")
        void uploadCertification_whenInputStreamCannotBeRead_shouldThrowStorageException() throws Exception {
            authenticateArtisan();
            MockMultipartFile unreadableFile = new MockMultipartFile(
                    "file", "broken.pdf", "application/pdf", "bytes".getBytes()) {
                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("stream unavailable");
                }
            };
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);

            assertThatThrownBy(() -> certificationService.uploadCertification(
                    unreadableFile, "Title", "Issuer", null, null))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to read uploaded certification stream");

            verify(storageService, never()).store(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("uploadCertification_whenCompensatingDeleteFails_shouldPreserveOriginalFailure")
        void uploadCertification_whenCompensatingDeleteFails_shouldPreserveOriginalFailure() throws Exception {
            authenticateArtisan();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "cert.pdf", "application/pdf", "bytes".getBytes());
            when(certificationRepository.countByArtisanIdAndDeletedAtIsNull(ARTISAN_ID)).thenReturn(0L);
            ValidatedFile validated = new ValidatedFile(
                    new ByteArrayInputStream(file.getBytes()), "cert.pdf", "application/pdf", file.getSize());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList()))
                    .thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);
            when(storageService.store(any(), anyString(), anyString(), anyLong()))
                    .thenReturn(new StorageResult("delete-fails-cert-key", "cert.pdf", "application/pdf", file.getSize(), Instant.now()));
            when(certificationRepository.save(any(ArtisanCertification.class)))
                    .thenThrow(new RuntimeException("database failure"));
            doThrow(new RuntimeException("delete failure")).when(storageService).delete("delete-fails-cert-key");

            assertThatThrownBy(() -> certificationService.uploadCertification(
                    file, "Title", "Issuer", null, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("database failure");
            verify(storageService).delete("delete-fails-cert-key");
        }

        /**
         * Verifies unauthenticated call throws UnauthorizedException.
         */
        @Test
        @DisplayName("uploadCertification_whenUnauthenticated_shouldThrowUnauthorizedException")
        void uploadCertification_whenUnauthenticated_shouldThrowUnauthorizedException() {
            SecurityContextHolder.clearContext();

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", null, null))
                    .isInstanceOf(UnauthorizedException.class);
        }

        /**
         * Verifies authenticated non-artisan role throws ForbiddenException.
         */
        @Test
        @DisplayName("uploadCertification_whenNonArtisanRole_shouldThrowForbiddenException")
        void uploadCertification_whenNonArtisanRole_shouldThrowForbiddenException() {
            SecurityContext securityContext = Mockito.mock(SecurityContext.class);
            Authentication authentication = Mockito.mock(Authentication.class);

            when(securityContext.getAuthentication()).thenReturn(authentication);
            when(authentication.isAuthenticated()).thenReturn(true);

            GrantedAuthority clientAuthority = Permission.Profile.READ;
            doReturn(List.of(clientAuthority)).when(authentication).getAuthorities();

            SecurityContextHolder.setContext(securityContext);

            MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "bytes".getBytes());

            assertThatThrownBy(() -> certificationService.uploadCertification(file, "Title", "Issuer", null, null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Access denied: " + Permission.Artisan.CONTENT.value() + " permission required.");
        }
    }

    @Nested
    @DisplayName("Get My Certifications Tests")
    class GetMyCertificationsTests {

        /**
         * Verifies retrieval of certifications sorted newest to oldest.
         */
        @Test
        @DisplayName("getMyCertifications_whenCertificationsExist_shouldReturnChronologicalList")
        void getMyCertifications_whenCertificationsExist_shouldReturnChronologicalList() {
            authenticateArtisan();

            ArtisanCertification c1 = ArtisanCertification.builder().title("Cert 1").issuer("Issuer 1").isVerified(true).build();
            c1.setId("c-1");
            ArtisanCertification c2 = ArtisanCertification.builder().title("Cert 2").issuer("Issuer 2").isVerified(false).build();
            c2.setId("c-2");

            when(certificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc(ARTISAN_ID))
                    .thenReturn(List.of(c1, c2));

            List<CertificationResponseDTO> result = certificationService.getMyCertifications();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo("c-1");
            assertThat(result.get(0).isVerified()).isTrue();
            assertThat(result.get(1).getId()).isEqualTo("c-2");
            assertThat(result.get(1).isVerified()).isFalse();
        }
    }

    @Nested
    @DisplayName("Delete Certification Tests")
    class DeleteCertificationTests {

        /**
         * Verifies soft deletion timestamp is stamped and entity is persisted.
         */
        @Test
        @DisplayName("deleteCertification_whenCertificationExistsAndOwned_shouldSetDeletedAtAndSave")
        void deleteCertification_whenCertificationExistsAndOwned_shouldSetDeletedAtAndSave() {
            authenticateArtisan();

            ArtisanCertification cert = ArtisanCertification.builder()
                    .artisan(testArtisan)
                    .title("Cert To Delete")
                    .issuer("Issuer")
                    .build();
            cert.setId("cert-del");

            when(certificationRepository.findByIdAndArtisanIdAndDeletedAtIsNull("cert-del", ARTISAN_ID))
                    .thenReturn(Optional.of(cert));

            certificationService.deleteCertification("cert-del");

            assertThat(cert.getDeletedAt()).isNotNull();
            verify(certificationRepository).save(cert);
        }

        /**
         * Verifies ResourceNotFoundException when certification is not found or owned by another artisan.
         */
        @Test
        @DisplayName("deleteCertification_whenNotFound_shouldThrowResourceNotFoundException")
        void deleteCertification_whenNotFound_shouldThrowResourceNotFoundException() {
            authenticateArtisan();

            when(certificationRepository.findByIdAndArtisanIdAndDeletedAtIsNull("unknown-cert", ARTISAN_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> certificationService.deleteCertification("unknown-cert"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Certification not found.");

            verify(certificationRepository, never()).save(any());
        }
    }
}
