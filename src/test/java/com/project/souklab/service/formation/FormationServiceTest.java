package com.project.souklab.service.formation;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.security.Permission;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.FormationProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.FormationReviewRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formation.FormationCreateDTO;
import com.project.souklab.dto.formation.FormationFileResponseDTO;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.dto.formation.FormationUpdateDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.filestorage.exception.FileTooLargeException;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.filestorage.lifecycle.StorageObjectLifecycle;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link FormationService} authoring constraints, teacher accreditation checks,
 * ownership controls, media uploads, compensating storage rollbacks, metadata reset policies,
 * and review submission validations.
 */
@ExtendWith(MockitoExtension.class)
    class FormationServiceTest {

    private static final String ARTISAN_EMAIL = "artisan_teacher@souklab.com";
    private static final String ARTISAN_ID = "artisan-uuid-100";
    private static final String OTHER_ARTISAN_ID = "artisan-uuid-200";

    @Mock
    private FormationRepository formationRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private FormationFileRepository formationFileRepository;

    @Mock
    private FormationEnrollmentRepository formationEnrollmentRepository;

    @Mock
    private FormationReviewRepository formationReviewRepository;

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

    @Mock
    private NotificationService notificationService;

    @Spy
    private FormationProperties formationProperties = new FormationProperties();

    @Spy
    private AppStorageProperties appStorageProperties = new AppStorageProperties();

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private FormationService formationService;

    private User testUser;
    private Artisan teacherArtisan;
    private Artisan nonTeacherArtisan;
    private Formation testFormation;

    @BeforeEach
    void setUp() {
        appStorageProperties.setFileServingPrefix("/api/v1/files/");
        formationProperties.setDefaultCurrency("DZD");
        formationProperties.getFile().setMaxCount(10);
        formationProperties.getFile().setMaxFileSize(DataSize.ofMegabytes(25));
        formationProperties.getFile().setAllowedMimeTypes(List.of("application/pdf", "image/jpeg", "image/png"));
        formationProperties.getThumbnail().setMaxFileSize(DataSize.ofMegabytes(10));
        formationProperties.getThumbnail().setAllowedMimeTypes(List.of("image/jpeg", "image/png"));
        lenient().when(fileUrlResolver.toStorageKey(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0, String.class).replace("/api/v1/files/", ""));
        testUser = User.builder()
                .email(ARTISAN_EMAIL)
                .firstName("Tahar")
                .lastName("Maitre")
                .build();
        testUser.setId(ARTISAN_ID);

        teacherArtisan = Artisan.builder()
                .id(ARTISAN_ID)
                .user(testUser)
                .city("Algiers")
                .isTeacher(true)
                .build();

        nonTeacherArtisan = Artisan.builder()
                .id(ARTISAN_ID)
                .user(testUser)
                .city("Algiers")
                .isTeacher(false)
                .build();

        testFormation = Formation.builder()
                .author(teacherArtisan)
                .title("Mastering Ceramics")
                .description("Intensive hands-on ceramics masterclass")
                .location("Bab El Oued, Algiers")
                .isOnline(false)
                .scheduledAt(LocalDateTime.now().plusDays(10))
                .durationHours(6)
                .maxParticipants(10)
                .price(12000)
                .currency("DZD")
                .status(FormationStatus.DRAFT)
                .build();
        testFormation.setId("formation-uuid-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Configures the security context with an authenticated artisan.
     */
    private void authenticateArtisan(Artisan artisan) {
        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        Authentication authentication = Mockito.mock(Authentication.class);

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ARTISAN_EMAIL);

        GrantedAuthority authority = Permission.Artisan.FORMATIONS;
        doReturn(List.of(authority)).when(authentication).getAuthorities();

        SecurityContextHolder.setContext(securityContext);
        when(artisanRepository.findByUserEmailIgnoreCase(ARTISAN_EMAIL)).thenReturn(Optional.of(artisan));
    }

    @Nested
    @DisplayName("Create Formation Tests")
    class CreateFormationTests {

        /**
         * Verifies accredited master artisan can create a draft formation.
         */
        @Test
        @DisplayName("createFormation_whenTeacherArtisan_shouldPersistDraftAndReturnDto")
        void createFormation_whenTeacherArtisan_shouldPersistDraftAndReturnDto() {
            authenticateArtisan(teacherArtisan);

            FormationCreateDTO dto = FormationCreateDTO.builder()
                    .title("Mastering Ceramics")
                    .description("Intensive hands-on ceramics masterclass")
                    .location("Bab El Oued, Algiers")
                    .isOnline(false)
                    .scheduledAt(LocalDateTime.now().plusDays(10))
                    .durationHours(6)
                    .maxParticipants(10)
                    .price(12000)
                    .currency("DZD")
                    .build();

            when(formationRepository.save(any(Formation.class))).thenAnswer(invocation -> {
                Formation entity = invocation.getArgument(0);
                entity.setId("formation-uuid-1");
                return entity;
            });
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull("formation-uuid-1")).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc("formation-uuid-1")).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus("formation-uuid-1", EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationResponseDTO response = formationService.createFormation(dto);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("formation-uuid-1");
            assertThat(response.getStatus()).isEqualTo(FormationStatus.DRAFT);
            assertThat(response.getTitle()).isEqualTo("Mastering Ceramics");
            verify(formationRepository).save(any(Formation.class));
        }

        /**
         * Verifies non-teacher artisan is rejected with ForbiddenException.
         */
        @Test
        @DisplayName("createFormation_whenNotTeacher_shouldThrowForbiddenException")
        void createFormation_whenNotTeacher_shouldThrowForbiddenException() {
            authenticateArtisan(nonTeacherArtisan);

            FormationCreateDTO dto = FormationCreateDTO.builder()
                    .title("Mastering Ceramics")
                    .description("Description")
                    .durationHours(4)
                    .maxParticipants(8)
                    .price(5000)
                    .build();

            assertThatThrownBy(() -> formationService.createFormation(dto))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Only accredited master artisans can create formations.");

            verify(formationRepository, never()).save(any());
        }

        @Test
        @DisplayName("createFormation_whenLocationIsOmitted_usesNullLocationAndConfiguredCurrency")
        void createFormation_whenLocationIsOmitted_preservesNullLocation() {
            authenticateArtisan(teacherArtisan);
            FormationCreateDTO dto = FormationCreateDTO.builder()
                    .title("Online Ceramics")
                    .description("Remote ceramics masterclass")
                    .location(null)
                    .isOnline(true)
                    .scheduledAt(LocalDateTime.now().plusDays(5))
                    .durationHours(2)
                    .maxParticipants(5)
                    .price(1000)
                    .currency(null)
                    .build();
            when(formationRepository.save(any(Formation.class))).thenAnswer(invocation -> {
                Formation entity = invocation.getArgument(0);
                entity.setId("online-formation");
                return entity;
            });
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull("online-formation"))
                    .thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc("online-formation"))
                    .thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus("online-formation", EnrollmentStatus.CONFIRMED))
                    .thenReturn(0L);

            FormationResponseDTO response = formationService.createFormation(dto);

            assertThat(response.getLocation()).isNull();
            assertThat(response.getCurrency()).isEqualTo("DZD");
        }

        @Test
        void createFormation_whenCurrencyIsBlank_usesConfiguredCurrency() {
            authenticateArtisan(teacherArtisan);
            FormationCreateDTO dto = FormationCreateDTO.builder()
                    .title("Blank Currency")
                    .description("Description")
                    .scheduledAt(LocalDateTime.now().plusDays(5))
                    .durationHours(2)
                    .maxParticipants(5)
                    .price(1000)
                    .currency(" ")
                    .build();
            when(formationRepository.save(any(Formation.class))).thenAnswer(invocation -> {
                Formation entity = invocation.getArgument(0);
                entity.setId("blank-currency");
                return entity;
            });
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull("blank-currency")).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc("blank-currency")).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus("blank-currency", EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            assertThat(formationService.createFormation(dto).getCurrency()).isEqualTo("DZD");
        }
    }

    @Nested
    @DisplayName("Update Formation Tests")
    class UpdateFormationTests {

        /**
         * Verifies author can update formation fields.
         */
        @Test
        @DisplayName("updateFormation_whenAuthor_shouldUpdateAndReturnDto")
        void updateFormation_whenAuthor_shouldUpdateAndReturnDto() {
            authenticateArtisan(teacherArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationUpdateDTO dto = FormationUpdateDTO.builder()
                    .title("Mastering Advanced Ceramics")
                    .description("Updated description")
                    .location("Algiers Center")
                    .isOnline(false)
                    .scheduledAt(testFormation.getScheduledAt())
                    .durationHours(8)
                    .maxParticipants(testFormation.getMaxParticipants())
                    .price(testFormation.getPrice())
                    .currency("DZD")
                    .build();

            FormationResponseDTO response = formationService.updateFormation(testFormation.getId(), dto);

            assertThat(response.getTitle()).isEqualTo("Mastering Advanced Ceramics");
            assertThat(response.getDescription()).isEqualTo("Updated description");
            assertThat(response.getStatus()).isEqualTo(FormationStatus.DRAFT);
        }

        /**
         * Verifies non-author cannot modify formation.
         */
        @Test
        @DisplayName("updateFormation_whenNotAuthor_shouldThrowForbiddenException")
        void updateFormation_whenNotAuthor_shouldThrowForbiddenException() {
            Artisan otherArtisan = Artisan.builder().id(OTHER_ARTISAN_ID).isTeacher(true).build();
            authenticateArtisan(otherArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));

            FormationUpdateDTO dto = FormationUpdateDTO.builder()
                    .title("Hacked Title")
                    .description("Description")
                    .build();

            assertThatThrownBy(() -> formationService.updateFormation(testFormation.getId(), dto))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("You do not have permission to modify this formation.");

            verify(formationRepository, never()).save(any());
        }

        /**
         * Verifies editing core metadata (date, price, capacity) on APPROVED formation resets status to PENDING_REVIEW.
         */
        @Test
        @DisplayName("updateFormation_whenApprovedAndCoreMetadataChanges_shouldResetToPendingReview")
        void updateFormation_whenApprovedAndCoreMetadataChanges_shouldResetToPendingReview() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.APPROVED);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationUpdateDTO dto = FormationUpdateDTO.builder()
                    .title(testFormation.getTitle())
                    .description(testFormation.getDescription())
                    .scheduledAt(testFormation.getScheduledAt())
                    .durationHours(testFormation.getDurationHours())
                    .maxParticipants(testFormation.getMaxParticipants())
                    .price(18000)
                    .currency("DZD")
                    .build();

            FormationResponseDTO response = formationService.updateFormation(testFormation.getId(), dto);

            assertThat(response.getStatus()).isEqualTo(FormationStatus.PENDING_REVIEW);
        }

        /**
         * Verifies editing non-core fields on APPROVED formation preserves approval status.
         */
        @Test
        @DisplayName("updateFormation_whenApprovedAndNonCoreFieldsChange_shouldPreserveApprovedStatus")
        void updateFormation_whenApprovedAndNonCoreFieldsChange_shouldPreserveApprovedStatus() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.APPROVED);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationUpdateDTO dto = FormationUpdateDTO.builder()
                    .title("Polished Title")
                    .description("Polished Description")
                    .scheduledAt(testFormation.getScheduledAt())
                    .durationHours(testFormation.getDurationHours())
                    .maxParticipants(testFormation.getMaxParticipants())
                    .price(testFormation.getPrice())
                    .currency("DZD")
                    .build();

            FormationResponseDTO response = formationService.updateFormation(testFormation.getId(), dto);

            assertThat(response.getStatus()).isEqualTo(FormationStatus.APPROVED);
        }

        @Test
        void updateFormation_whenPublishedAndCoreMetadataChanges_shouldResetToPendingReview() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.PUBLISHED);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            FormationUpdateDTO dto = FormationUpdateDTO.builder()
                    .title(testFormation.getTitle()).description(testFormation.getDescription())
                    .scheduledAt(testFormation.getScheduledAt()).durationHours(testFormation.getDurationHours())
                    .maxParticipants(testFormation.getMaxParticipants() + 1).price(testFormation.getPrice()).currency("DZD").build();

            assertThat(formationService.updateFormation(testFormation.getId(), dto).getStatus())
                    .isEqualTo(FormationStatus.PENDING_REVIEW);
        }
    }

    @Nested
    @DisplayName("Thumbnail Upload Tests")
    class ThumbnailUploadTests {

        /**
         * Verifies successful thumbnail upload stores file and updates formation URL.
         */
        @Test
        @DisplayName("uploadThumbnail_whenValidImage_shouldStoreAndSetThumbnailUrl")
        void uploadThumbnail_whenValidImage_shouldStoreAndSetThumbnailUrl() throws Exception {
            authenticateArtisan(teacherArtisan);

            MockMultipartFile file = new MockMultipartFile("file", "thumb.jpg", "image/jpeg", "image bytes".getBytes());
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "thumb.jpg", "image/jpeg", file.getSize());

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("key-thumb-1", "thumb.jpg", "image/jpeg", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationResponseDTO response = formationService.uploadThumbnail(testFormation.getId(), file);

            assertThat(response.getThumbnailUrl()).isEqualTo("/api/v1/files/key-thumb-1");
            verify(storageService).store(any(), anyString(), anyString(), anyLong());
        }

        /**
         * Verifies compensating storage rollback when database save fails during thumbnail upload.
         */
        @Test
        @DisplayName("uploadThumbnail_whenDatabaseSaveFails_shouldRollbackStorageFile")
        void uploadThumbnail_whenDatabaseSaveFails_shouldRollbackStorageFile() throws Exception {
            authenticateArtisan(teacherArtisan);

            MockMultipartFile file = new MockMultipartFile("file", "thumb.jpg", "image/jpeg", "image bytes".getBytes());
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "thumb.jpg", "image/jpeg", file.getSize());

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("key-rollback-thumb", "thumb.jpg", "image/jpeg", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);
            when(formationRepository.save(any(Formation.class))).thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), file))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB error");

            verify(storageService).delete("key-rollback-thumb");
        }

        /**
         * Verifies thumbnail upload rejects oversized file with FileTooLargeException.
         */
        @Test
        @DisplayName("uploadThumbnail_whenFileSizeExceeds10Mb_shouldThrowFileTooLargeException")
        void uploadThumbnail_whenFileSizeExceeds10Mb_shouldThrowFileTooLargeException() {
            authenticateArtisan(teacherArtisan);

            long oversized = (10L * 1024 * 1024) + 1024;
            MockMultipartFile largeFile = new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[10]) {
                @Override
                public long getSize() {
                    return oversized;
                }
            };

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), largeFile))
                    .isInstanceOf(FileTooLargeException.class);

            verify(storageService, never()).store(any(), anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("uploadThumbnail_whenInputStreamCannotBeRead_shouldThrowStorageException")
        void uploadThumbnail_whenInputStreamCannotBeRead_shouldThrowStorageException() throws Exception {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            MultipartFile unreadable = new MockMultipartFile(
                    "file", "broken.jpg", "image/jpeg", "bytes".getBytes()) {
                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("stream unavailable");
                }
            };

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), unreadable))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Failed to read upload stream");
            verifyNoInteractions(storageService, virusScanService);
        }

        @Test
        void uploadThumbnail_whenFileIsEmpty_shouldThrowBadRequestException() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            MultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), empty))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void uploadThumbnail_whenStorageFailsBeforeKey_doesNotDeleteUnknownKey() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            MultipartFile file = new MockMultipartFile("file", "thumb.jpg", "image/jpeg", new byte[]{1});
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(new byte[]{1}), "thumb.jpg", "image/jpeg", 1);
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);
            when(storageService.store(any(), anyString(), anyString(), anyLong()))
                    .thenThrow(new IllegalStateException("storage failure"));

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), file))
                    .isInstanceOf(IllegalStateException.class);
            verify(storageService, never()).delete(anyString());
        }
    }

    @Nested
    @DisplayName("Course File Upload Tests")
    class CourseFileUploadTests {

        /**
         * Verifies successful course document upload stores file and creates entity.
         */
        @Test
        @DisplayName("uploadCourseFile_whenValidDocument_shouldPersistAndReturnDto")
        void uploadCourseFile_whenValidDocument_shouldPersistAndReturnDto() throws Exception {
            authenticateArtisan(teacherArtisan);

            MockMultipartFile file = new MockMultipartFile("file", "syllabus.pdf", "application/pdf", "pdf bytes".getBytes());
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "syllabus.pdf", "application/pdf", file.getSize());

            when(formationRepository.findWithLockByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("key-file-1", "syllabus.pdf", "application/pdf", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);

            FormationFile savedFile = FormationFile.builder()
                    .formation(testFormation)
                    .storageKey("key-file-1")
                    .originalFilename("syllabus.pdf")
                    .contentType("application/pdf")
                    .fileSize(file.getSize())
                    .build();
            savedFile.setId("file-uuid-1");

            when(formationFileRepository.save(any(FormationFile.class))).thenReturn(savedFile);

            FormationFileResponseDTO response = formationService.uploadCourseFile(testFormation.getId(), file);

            assertThat(response.getId()).isEqualTo("file-uuid-1");
            assertThat(response.getOriginalFilename()).isEqualTo("syllabus.pdf");
            assertThat(response.getDownloadUrl()).isEqualTo("/api/v1/files/key-file-1");
        }

        /**
         * Verifies quota limit (max 10 course files) is enforced.
         */
        @Test
        @DisplayName("uploadCourseFile_whenQuotaReached_shouldThrowBadRequestException")
        void uploadCourseFile_whenQuotaReached_shouldThrowBadRequestException() {
            authenticateArtisan(teacherArtisan);

            List<FormationFile> fullFiles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                fullFiles.add(new FormationFile());
            }

            when(formationRepository.findWithLockByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(fullFiles);

            MockMultipartFile file = new MockMultipartFile("file", "eleventh.pdf", "application/pdf", "bytes".getBytes());

            assertThatThrownBy(() -> formationService.uploadCourseFile(testFormation.getId(), file))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Course file quota reached. Maximum 10 files allowed.");

            verify(storageService, never()).store(any(), anyString(), anyString(), anyLong());
        }

        /**
         * Verifies compensating storage rollback when course file database save fails.
         */
        @Test
        @DisplayName("uploadCourseFile_whenDatabaseSaveFails_shouldRollbackStorageFile")
        void uploadCourseFile_whenDatabaseSaveFails_shouldRollbackStorageFile() throws Exception {
            authenticateArtisan(teacherArtisan);

            MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", "pdf bytes".getBytes());
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(file.getBytes()), "notes.pdf", "application/pdf", file.getSize());

            when(formationRepository.findWithLockByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList())).thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);

            StorageResult storageResult = new StorageResult("key-rollback-course-file", "notes.pdf", "application/pdf", file.getSize(), Instant.now());
            when(storageService.store(any(), anyString(), anyString(), anyLong())).thenReturn(storageResult);
            when(formationFileRepository.save(any(FormationFile.class))).thenThrow(new RuntimeException("DB failure"));

            assertThatThrownBy(() -> formationService.uploadCourseFile(testFormation.getId(), file))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB failure");

            verify(storageService).delete("key-rollback-course-file");
        }
    }

    @Nested
    @DisplayName("Submit For Review Tests")
    class SubmitForReviewTests {

        /**
         * Verifies submitting a complete draft transitions to PENDING_REVIEW and sends admin notification.
         */
        @Test
        @DisplayName("submitForReview_whenDraftAndComplete_shouldSetPendingReviewAndNotifyAdmins")
        void submitForReview_whenDraftAndComplete_shouldSetPendingReviewAndNotifyAdmins() {
            authenticateArtisan(teacherArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(testFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationResponseDTO response = formationService.submitForReview(testFormation.getId());

            assertThat(response.getStatus()).isEqualTo(FormationStatus.PENDING_REVIEW);
            verify(notificationService).notifyAdmins("New formation submitted for review: Mastering Ceramics");
        }

        /**
         * Verifies submitting from APPROVED status throws ConflictException.
         */
        @Test
        @DisplayName("submitForReview_whenAlreadyApproved_shouldThrowConflictException")
        void submitForReview_whenAlreadyApproved_shouldThrowConflictException() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.APPROVED);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.submitForReview(testFormation.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Formation can only be submitted for review from DRAFT or REJECTED status.");

            verify(notificationService, never()).notifyAdmins(anyString());
        }

        /**
         * Verifies submitting with missing scheduled date throws BadRequestException.
         */
        @Test
        @DisplayName("submitForReview_whenMissingScheduledDate_shouldThrowBadRequestException")
        void submitForReview_whenMissingScheduledDate_shouldThrowBadRequestException() {
            authenticateArtisan(teacherArtisan);
            testFormation.setScheduledAt(null);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.submitForReview(testFormation.getId()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Required fields missing or invalid for review submission.");
        }

        @Test
        void submitForReview_rejectsEachInvalidRequiredField() {
            List<Consumer<Formation>> invalidations = List.of(
                    formation -> formation.setTitle(null),
                    formation -> formation.setTitle(" "),
                    formation -> formation.setDescription(null),
                    formation -> formation.setDescription(" "),
                    formation -> formation.setScheduledAt(null),
                    formation -> formation.setDurationHours(0),
                    formation -> formation.setMaxParticipants(0)
            );

            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));

            for (Consumer<Formation> invalidation : invalidations) {
                testFormation.setStatus(FormationStatus.DRAFT);
                testFormation.setTitle("Valid title");
                testFormation.setDescription("Valid description");
                testFormation.setScheduledAt(LocalDateTime.now().plusDays(10));
                testFormation.setDurationHours(6);
                testFormation.setMaxParticipants(10);
                invalidation.accept(testFormation);

                assertThatThrownBy(() -> formationService.submitForReview(testFormation.getId()))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("Required fields missing or invalid for review submission.");
            }
        }
    }

    @Nested
    @DisplayName("Delete and Query Tests")
    class DeleteAndQueryTests {

        /**
         * Verifies soft deletion of formation.
         */
        @Test
        @DisplayName("deleteFormation_whenAuthor_shouldSetDeletedAt")
        void deleteFormation_whenAuthor_shouldSetDeletedAt() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));

            formationService.deleteFormation(testFormation.getId());

            assertThat(testFormation.getDeletedAt()).isNotNull();
            verify(formationRepository).save(testFormation);
        }

        @Test
        @DisplayName("deleteFormation_whenCourseFilesExist_softDeletesFilesAndSchedulesTheirStorageCleanup")
        void deleteFormation_whenCourseFilesExist_softDeletesFilesAndSchedulesCleanup() {
            authenticateArtisan(teacherArtisan);
            FormationFile file = FormationFile.builder()
                    .formation(testFormation)
                    .storageKey("course-key")
                    .originalFilename("guide.pdf")
                    .build();
            file.setId("course-file");
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(List.of(file));

            formationService.deleteFormation(testFormation.getId());

            assertThat(file.getDeletedAt()).isNotNull();
            verify(formationFileRepository).save(file);
            verify(storageObjectLifecycle).deleteAfterCommit("course-key");
            verify(formationRepository).save(testFormation);
        }

        /**
         * Verifies soft deletion of course file.
         */
        @Test
        @DisplayName("deleteCourseFile_whenAuthor_shouldSetDeletedAt")
        void deleteCourseFile_whenAuthor_shouldSetDeletedAt() {
            authenticateArtisan(teacherArtisan);

            FormationFile file = FormationFile.builder()
                    .formation(testFormation)
                    .storageKey("key-1")
                    .originalFilename("guide.pdf")
                    .build();
            file.setId("file-1");

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId())).thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull("file-1", testFormation.getId())).thenReturn(Optional.of(file));

            formationService.deleteCourseFile(testFormation.getId(), "file-1");

            assertThat(file.getDeletedAt()).isNotNull();
            verify(formationFileRepository).save(file);
        }

        /**
         * Verifies getMyFormations returns paginated cards.
         */
        @Test
        @DisplayName("getMyFormations_whenAuthor_shouldReturnPaginatedResponse")
        void getMyFormations_whenAuthor_shouldReturnPaginatedResponse() {
            authenticateArtisan(teacherArtisan);

            Pageable pageable = PageRequest.of(0, 10);
            when(formationRepository.findByAuthorIdAndDeletedAtIsNull(ARTISAN_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(testFormation)));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED))
                    .thenReturn(3L);

            PaginatedResponse<FormationSummaryDTO> response = formationService.getMyFormations(pageable);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getTitle()).isEqualTo("Mastering Ceramics");
            assertThat(response.getContent().get(0).getActiveEnrollmentsCount()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("Remaining authoring and retrieval branches")
    class RemainingBranchesTests {

        @Test
        void uploadThumbnailRejectsMissingFile() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), null))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void uploadCourseFileRejectsMissingAndOversizedFiles() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findWithLockByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            MultipartFile empty = new MockMultipartFile("file", "", "application/pdf", new byte[0]);
            assertThatThrownBy(() -> formationService.uploadCourseFile(testFormation.getId(), empty))
                    .isInstanceOf(BadRequestException.class);

            MultipartFile oversized = Mockito.mock(MultipartFile.class);
            when(oversized.isEmpty()).thenReturn(false);
            when(oversized.getSize()).thenReturn(DataSize.ofMegabytes(25).toBytes() + 1);
            assertThatThrownBy(() -> formationService.uploadCourseFile(testFormation.getId(), oversized))
                    .isInstanceOf(FileTooLargeException.class);
        }

        @Test
        void deleteCourseFileRejectsUnknownFile() {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull("missing", testFormation.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> formationService.deleteCourseFile(testFormation.getId(), "missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getsTeacherFormationsAndOwnedDetails() {
            Pageable pageable = PageRequest.of(0, 10);
            when(formationRepository.findByAuthorIdAndStatusAndDeletedAtIsNull(
                    OTHER_ARTISAN_ID, FormationStatus.PUBLISHED, pageable))
                    .thenReturn(new PageImpl<>(List.of(testFormation)));
            assertThat(formationService.getTeacherFormations(OTHER_ARTISAN_ID, pageable).getContent())
                    .hasSize(1);

            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            assertThat(formationService.getFormationDetails(testFormation.getId()).getTitle())
                    .isEqualTo(testFormation.getTitle());
        }

        @Test
        void missingFormationAndFailedCompensatingDeleteAreHandled() throws Exception {
            authenticateArtisan(teacherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull("missing"))
                    .thenReturn(Optional.empty());
            assertThatThrownBy(() -> formationService.getFormationDetails("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);

            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            MultipartFile file = new MockMultipartFile("file", "thumb.jpg", "image/jpeg", new byte[]{1});
            ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(new byte[]{1}),
                    "thumb.jpg", "image/jpeg", 1);
            when(fileValidator.validateAndSanitize(any(), anyString(), anyString(), anyLong(), anyList()))
                    .thenReturn(validated);
            when(virusScanService.scan(validated)).thenReturn(validated);
            when(storageService.store(any(), anyString(), anyString(), anyLong()))
                    .thenReturn(new StorageResult("compensate-key", "thumb.jpg", "image/jpeg", 1, Instant.now()));
            when(formationRepository.save(testFormation)).thenThrow(new IllegalStateException("db failure"));
            doThrow(new IllegalStateException("storage unavailable")).when(storageService).delete("compensate-key");

            assertThatThrownBy(() -> formationService.uploadThumbnail(testFormation.getId(), file))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db failure");
            verify(storageService).delete("compensate-key");
        }

        @Test
        void cancelFormation_whenPublishedWithConfirmedEnrollments_cancelsAndNotifies() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.PUBLISHED);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));
            when(formationRepository.save(any(Formation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            User participantUser = User.builder().email("participant@souklab.com").firstName("Karim").lastName("Artisan").build();
            participantUser.setId("user-part-1");
            Artisan participantArtisan = Artisan.builder().id("artisan-part-1").user(participantUser).build();
            FormationEnrollment enrollment = FormationEnrollment.builder()
                    .formation(testFormation)
                    .artisan(participantArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .build();

            when(formationEnrollmentRepository.findByFormationIdAndStatus(testFormation.getId(), EnrollmentStatus.CONFIRMED))
                    .thenReturn(List.of(enrollment));

            FormationResponseDTO result = formationService.cancelFormation(testFormation.getId());

            assertThat(result.getStatus()).isEqualTo(FormationStatus.CANCELLED);
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            verify(formationEnrollmentRepository).save(enrollment);
            verify(notificationService).createForUser(
                    eq(participantUser),
                    anyString(),
                    eq(NotificationType.Formation.CANCELLED),
                    eq(testFormation.getId())
            );
        }

        @Test
        void cancelFormation_whenNotInCancellableState_throwsConflictException() {
            authenticateArtisan(teacherArtisan);
            testFormation.setStatus(FormationStatus.COMPLETED);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.cancelFormation(testFormation.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Formation cannot be cancelled in its current status");
        }

        @Test
        void cancelFormation_whenNotOwner_throwsForbiddenException() {
            Artisan otherArtisan = Artisan.builder().id(OTHER_ARTISAN_ID).isTeacher(true).build();
            authenticateArtisan(otherArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(testFormation.getId()))
                    .thenReturn(Optional.of(testFormation));

            assertThatThrownBy(() -> formationService.cancelFormation(testFormation.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
