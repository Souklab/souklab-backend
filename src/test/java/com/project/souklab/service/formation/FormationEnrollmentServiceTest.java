package com.project.souklab.service.formation;
import com.project.souklab.security.Permission;
import com.project.souklab.config.FormationProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dto.formation.FormationEnrollmentDetailDTO;
import com.project.souklab.dto.formation.FormationEnrollmentResponseDTO;
import com.project.souklab.dto.formation.FormationPublicViewDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResource;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link FormationEnrollmentService} peer enrollment workflows,
 * capacity bounds, cancellation deadline cutoff rules, and gated file downloads.
 */
@ExtendWith(MockitoExtension.class)
class FormationEnrollmentServiceTest {

    private static final String INSTRUCTOR_EMAIL = "instructor@souklab.com";
    private static final String INSTRUCTOR_ID = "artisan-instructor-1";
    private static final String PEER_EMAIL = "peer_artisan@souklab.com";
    private static final String PEER_ID = "artisan-peer-1";
    private static final String FORMATION_ID = "formation-uuid-101";
    private static final String FILE_ID = "file-uuid-201";

    @Mock
    private FormationRepository formationRepository;

    @Mock
    private FormationEnrollmentRepository formationEnrollmentRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private FormationFileRepository formationFileRepository;

    @Mock
    private StorageService storageService;

    @Spy
    private FormationProperties formationProperties = new FormationProperties();

    @Spy
    private Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    private FormationEnrollmentService formationEnrollmentService;

    private User instructorUser;
    private Artisan instructorArtisan;
    private User peerUser;
    private Artisan peerArtisan;
    private Formation publishedFormation;
    private FormationFile courseFile;

    @BeforeEach
    void setUp() {
        formationProperties.getCancellation().setDeadlineHours(24);
        formationProperties.getPagination().setDefaultPageSize(10);
        instructorUser = User.builder()
                .email(INSTRUCTOR_EMAIL)
                .firstName("Karim")
                .lastName("Maitre")
                .build();
        instructorUser.setId(INSTRUCTOR_ID);

        instructorArtisan = Artisan.builder()
                .id(INSTRUCTOR_ID)
                .user(instructorUser)
                .city("Algiers")
                .isTeacher(true)
                .build();

        peerUser = User.builder()
                .email(PEER_EMAIL)
                .firstName("Amine")
                .lastName("Artisan")
                .build();
        peerUser.setId(PEER_ID);

        peerArtisan = Artisan.builder()
                .id(PEER_ID)
                .user(peerUser)
                .city("Oran")
                .isTeacher(false)
                .build();

        publishedFormation = Formation.builder()
                .author(instructorArtisan)
                .title("Traditional Leather Crafting Masterclass")
                .description("In-depth masterclass exploring traditional tanning and stitching methods.")
                .location("Casbah, Algiers")
                .isOnline(false)
                .scheduledAt(LocalDateTime.now().plusDays(7))
                .durationHours(8)
                .maxParticipants(10)
                .price(15000)
                .currency("DZD")
                .status(FormationStatus.PUBLISHED)
                .build();
        publishedFormation.setId(FORMATION_ID);

        courseFile = FormationFile.builder()
                .formation(publishedFormation)
                .storageKey("formations/" + FORMATION_ID + "/syllabus.pdf")
                .originalFilename("syllabus.pdf")
                .contentType("application/pdf")
                .fileSize(2048576L)
                .build();
        courseFile.setId(FILE_ID);
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
        when(authentication.getName()).thenReturn(artisan.getUser().getEmail());

        GrantedAuthority authority = Permission.Artisan.FORMATIONS;
        doReturn(List.of(authority)).when(authentication).getAuthorities();

        SecurityContextHolder.setContext(securityContext);

        when(artisanRepository.findByUserEmailIgnoreCase(artisan.getUser().getEmail()))
                .thenReturn(Optional.of(artisan));
    }

    @Nested
    @DisplayName("Catalog & Formation Details")
    class CatalogTests {

        /**
         * Verifies retrieving the published catalog returns mapped summary cards with enrollment counts.
         */
        @Test
        @DisplayName("getPublishedCatalog_shouldReturnMappedPageWithActiveEnrollments")
        void getPublishedCatalog_shouldReturnMappedPageWithActiveEnrollments() {
            Page<Formation> formationPage = new PageImpl<>(List.of(publishedFormation));
            when(formationRepository.findByStatusAndDeletedAtIsNull(eq(FormationStatus.PUBLISHED), any(Pageable.class)))
                    .thenReturn(formationPage);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(3L);

            Page<FormationSummaryDTO> result = formationEnrollmentService.getPublishedCatalog(PageRequest.of(0, 10));

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(FORMATION_ID);
            assertThat(result.getContent().get(0).getActiveEnrollmentsCount()).isEqualTo(3L);
        }

        @Test
        void getPublishedCatalog_usesConfiguredDefaultsAndPreservesSortedPageable() {
            when(formationRepository.findByStatusAndDeletedAtIsNull(eq(FormationStatus.PUBLISHED), any(Pageable.class)))
                    .thenReturn(Page.empty());
            formationEnrollmentService.getPublishedCatalog(null);
            formationEnrollmentService.getPublishedCatalog(PageRequest.of(0, 10, Sort.by("title")));
            verify(formationRepository, Mockito.times(2))
                    .findByStatusAndDeletedAtIsNull(eq(FormationStatus.PUBLISHED), any(Pageable.class));
        }

        /**
         * Verifies published formation details when viewed by the author includes active download links.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenAuthorViews_shouldPopulateDownloadUrls")
        void getPublishedFormationDetails_whenAuthorViews_shouldPopulateDownloadUrls() {
            authenticateArtisan(instructorArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, INSTRUCTOR_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(4L);
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(List.of(courseFile));

            FormationPublicViewDTO result = formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(FORMATION_ID);
            assertThat(result.isEnrolled()).isFalse();
            assertThat(result.getAvailableSeats()).isEqualTo(6L);
            assertThat(result.getFiles()).hasSize(1);
            assertThat(result.getFiles().get(0).getDownloadUrl())
                    .isEqualTo("/api/v1/artisan/formations/" + FORMATION_ID + "/files/" + FILE_ID + "/download");
        }

        /**
         * Verifies published formation details when viewed by confirmed enrolled peer artisan includes download links.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenEnrolledPeerViews_shouldPopulateDownloadUrls")
        void getPublishedFormationDetails_whenEnrolledPeerViews_shouldPopulateDownloadUrls() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(true);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(4L);
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(List.of(courseFile));

            FormationPublicViewDTO result = formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID);

            assertThat(result).isNotNull();
            assertThat(result.isEnrolled()).isTrue();
            assertThat(result.getFiles()).hasSize(1);
            assertThat(result.getFiles().get(0).getDownloadUrl()).isNotNull();
        }

        @Test
        @DisplayName("getPublishedFormationDetails_whenAttendedPeerViewsCompletedFormation_shouldPopulateDownloadUrls")
        void getPublishedFormationDetails_whenAttendedPeerViewsCompletedFormation_shouldPopulateDownloadUrls() {
            authenticateArtisan(peerArtisan);
            publishedFormation.setStatus(FormationStatus.COMPLETED);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.ATTENDED))
                    .thenReturn(true);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(0L);
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(List.of(courseFile));

            FormationPublicViewDTO result = formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID);

            assertThat(result).isNotNull();
            assertThat(result.isEnrolled()).isTrue();
            assertThat(result.getFiles()).hasSize(1);
            assertThat(result.getFiles().get(0).getDownloadUrl()).isNotNull();
        }

        /**
         * Verifies published formation details when viewed by non-enrolled peer artisan has null download links.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenNonEnrolledPeerViews_downloadUrlShouldBeNull")
        void getPublishedFormationDetails_whenNonEnrolledPeerViews_downloadUrlShouldBeNull() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(2L);
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(List.of(courseFile));

            FormationPublicViewDTO result = formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID);

            assertThat(result).isNotNull();
            assertThat(result.isEnrolled()).isFalse();
            assertThat(result.getFiles()).hasSize(1);
            assertThat(result.getFiles().get(0).getDownloadUrl()).isNull();
        }

        /**
         * Verifies that accessing a formation that is not published throws ResourceNotFoundException.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenNotPublished_shouldThrowNotFound")
        void getPublishedFormationDetails_whenNotPublished_shouldThrowNotFound() {
            publishedFormation.setStatus(FormationStatus.DRAFT);
            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));

            assertThatThrownBy(() -> formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Published formation not found");
        }

        @Test
        void getPublishedFormationDetails_usesLockResultWhenAvailable() {
            authenticateArtisan(instructorArtisan);
            when(formationRepository.findWithLockByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                    FORMATION_ID, INSTRUCTOR_ID, EnrollmentStatus.CONFIRMED)).thenReturn(false);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(0L);
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(FORMATION_ID)).thenReturn(List.of());

            assertThat(formationEnrollmentService.getPublishedFormationDetails(FORMATION_ID)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Enrollment Workflow")
    class EnrollmentTests {

        /**
         * Verifies happy path peer artisan enrollment when capacity is available.
         */
        @Test
        @DisplayName("enrollInFormation_happyPath_shouldReturnConfirmedEnrollment")
        void enrollInFormation_happyPath_shouldReturnConfirmedEnrollment() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(5L);
            when(formationEnrollmentRepository.findByFormationIdAndArtisanId(FORMATION_ID, PEER_ID))
                    .thenReturn(Optional.empty());

            FormationEnrollment savedEnrollment = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now())
                    .build();
            savedEnrollment.setId("enrollment-100");

            when(formationEnrollmentRepository.save(any(FormationEnrollment.class)))
                    .thenReturn(savedEnrollment);

            FormationEnrollmentResponseDTO response = formationEnrollmentService.enrollInFormation(FORMATION_ID);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo("enrollment-100");
            assertThat(response.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(response.getArtisanId()).isEqualTo(PEER_ID);
            assertThat(response.getFormationId()).isEqualTo(FORMATION_ID);
        }

        /**
         * Verifies that authors cannot enroll in their own formations.
         */
        @Test
        @DisplayName("enrollInFormation_whenAuthorAttemptsEnrollment_shouldThrow409Conflict")
        void enrollInFormation_whenAuthorAttemptsEnrollment_shouldThrow409Conflict() {
            authenticateArtisan(instructorArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));

            assertThatThrownBy(() -> formationEnrollmentService.enrollInFormation(FORMATION_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Authors cannot enroll in their own formations.");

            verify(formationEnrollmentRepository, never()).save(any());
        }

        /**
         * Verifies that enrollment is rejected when maximum participant capacity is reached.
         */
        @Test
        @DisplayName("enrollInFormation_whenCapacityReached_shouldThrow409Conflict")
        void enrollInFormation_whenCapacityReached_shouldThrow409Conflict() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(10L);

            assertThatThrownBy(() -> formationEnrollmentService.enrollInFormation(FORMATION_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Formation is fully booked. Maximum capacity reached.");

            verify(formationEnrollmentRepository, never()).save(any());
        }

        /**
         * Verifies that duplicate confirmed enrollment is rejected with 409 Conflict.
         */
        @Test
        @DisplayName("enrollInFormation_whenAlreadyEnrolledConfirmed_shouldThrow409Conflict")
        void enrollInFormation_whenAlreadyEnrolledConfirmed_shouldThrow409Conflict() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(3L);

            FormationEnrollment existing = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now().minusDays(1))
                    .build();

            when(formationEnrollmentRepository.findByFormationIdAndArtisanId(FORMATION_ID, PEER_ID))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> formationEnrollmentService.enrollInFormation(FORMATION_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("You are already enrolled in this formation.");

            verify(formationEnrollmentRepository, never()).save(any());
        }

        /**
         * Verifies that previously cancelled enrollment is reactivated with CONFIRMED status.
         */
        @Test
        @DisplayName("enrollInFormation_whenPreviouslyCancelled_shouldReactivateSeat")
        void enrollInFormation_whenPreviouslyCancelled_shouldReactivateSeat() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(2L);

            FormationEnrollment cancelledEnrollment = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CANCELLED)
                    .enrolledAt(LocalDateTime.now().minusDays(5))
                    .cancelledAt(LocalDateTime.now().minusDays(2))
                    .build();
            cancelledEnrollment.setId("enrollment-reactivate-1");

            when(formationEnrollmentRepository.findByFormationIdAndArtisanId(FORMATION_ID, PEER_ID))
                    .thenReturn(Optional.of(cancelledEnrollment));
            when(formationEnrollmentRepository.save(any(FormationEnrollment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FormationEnrollmentResponseDTO response = formationEnrollmentService.enrollInFormation(FORMATION_ID);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(response.getCancelledAt()).isNull();
            assertThat(cancelledEnrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(cancelledEnrollment.getCancelledAt()).isNull();
        }

        @Test
        void enrollInFormation_rejectsPreviouslyAttendedEnrollment() {
            authenticateArtisan(peerArtisan);
            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID)).thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED)).thenReturn(1L);
            FormationEnrollment attended = FormationEnrollment.builder().formation(publishedFormation)
                    .artisan(peerArtisan).status(EnrollmentStatus.ATTENDED).build();
            when(formationEnrollmentRepository.findByFormationIdAndArtisanId(FORMATION_ID, PEER_ID))
                    .thenReturn(Optional.of(attended));

            assertThatThrownBy(() -> formationEnrollmentService.enrollInFormation(FORMATION_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ATTENDED");
        }
    }

    @Nested
    @DisplayName("Cancellation Policy")
    class CancellationTests {

        /**
         * Verifies that cancellation prior to the 24-hour cutoff deadline succeeds.
         */
        @Test
        @DisplayName("cancelEnrollment_priorToCutoff_shouldSucceed")
        void cancelEnrollment_priorToCutoff_shouldSucceed() {
            authenticateArtisan(peerArtisan);

            publishedFormation.setScheduledAt(LocalDateTime.now().plusHours(48));

            FormationEnrollment activeEnrollment = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now().minusDays(1))
                    .build();
            activeEnrollment.setId("enrollment-cancel-1");

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.findByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(Optional.of(activeEnrollment));
            when(formationEnrollmentRepository.save(any(FormationEnrollment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FormationEnrollmentResponseDTO response = formationEnrollmentService.cancelEnrollment(FORMATION_ID);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(response.getCancelledAt()).isNotNull();
            assertThat(activeEnrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        }

        /**
         * Verifies that cancellation within the 24-hour cutoff window throws BadRequestException.
         */
        @Test
        @DisplayName("cancelEnrollment_withinCutoffWindow_shouldThrowBadRequest")
        void cancelEnrollment_withinCutoffWindow_shouldThrowBadRequest() {
            authenticateArtisan(peerArtisan);

            publishedFormation.setScheduledAt(LocalDateTime.now().plusHours(12));

            FormationEnrollment activeEnrollment = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now().minusDays(1))
                    .build();

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.findByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(Optional.of(activeEnrollment));

            assertThatThrownBy(() -> formationEnrollmentService.cancelEnrollment(FORMATION_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cancellations must be made at least 24 hours before");

            verify(formationEnrollmentRepository, never()).save(any());
        }

        /**
         * Verifies that cancelling without an active enrollment throws ResourceNotFoundException.
         */
        @Test
        @DisplayName("cancelEnrollment_whenNoActiveEnrollment_shouldThrowNotFound")
        void cancelEnrollment_whenNoActiveEnrollment_shouldThrowNotFound() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.findByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> formationEnrollmentService.cancelEnrollment(FORMATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Active confirmed enrollment not found");
        }

        @Test
        void cancelEnrollment_allowsCancellationWhenFormationHasNoSchedule() {
            authenticateArtisan(peerArtisan);
            publishedFormation.setScheduledAt(null);
            FormationEnrollment active = FormationEnrollment.builder().formation(publishedFormation)
                    .artisan(peerArtisan).status(EnrollmentStatus.CONFIRMED).build();
            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID)).thenReturn(Optional.of(publishedFormation));
            when(formationEnrollmentRepository.findByFormationIdAndArtisanIdAndStatus(
                    FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED)).thenReturn(Optional.of(active));
            when(formationEnrollmentRepository.save(any(FormationEnrollment.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(formationEnrollmentService.cancelEnrollment(FORMATION_ID).getStatus())
                    .isEqualTo(EnrollmentStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("Protected Course File Downloads & History")
    class FileDownloadAndHistoryTests {

        /**
         * Verifies downloading a course file succeeds when caller is confirmed enrolled participant.
         */
        @Test
        @DisplayName("downloadCourseFile_whenEnrolledParticipant_shouldReturnStorageResource")
        void downloadCourseFile_whenEnrolledParticipant_shouldReturnStorageResource() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull(FILE_ID, FORMATION_ID))
                    .thenReturn(Optional.of(courseFile));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(true);

            StorageResource mockResource = new StorageResource(
                    courseFile.getStorageKey(),
                    new ByteArrayInputStream("pdf content".getBytes()),
                    courseFile.getContentType(),
                    courseFile.getFileSize(),
                    courseFile.getOriginalFilename()
            );
            when(storageService.load(courseFile.getStorageKey())).thenReturn(mockResource);

            StorageResource result = formationEnrollmentService.downloadCourseFile(FORMATION_ID, FILE_ID);

            assertThat(result).isNotNull();
            assertThat(result.key()).isEqualTo(courseFile.getStorageKey());
            assertThat(result.contentType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("downloadCourseFile_whenAttendedParticipant_shouldReturnStorageResource")
        void downloadCourseFile_whenAttendedParticipant_shouldReturnStorageResource() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull(FILE_ID, FORMATION_ID))
                    .thenReturn(Optional.of(courseFile));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.ATTENDED))
                    .thenReturn(true);

            StorageResource mockResource = new StorageResource(
                    courseFile.getStorageKey(),
                    new ByteArrayInputStream("pdf content".getBytes()),
                    courseFile.getContentType(),
                    courseFile.getFileSize(),
                    courseFile.getOriginalFilename()
            );
            when(storageService.load(courseFile.getStorageKey())).thenReturn(mockResource);

            StorageResource result = formationEnrollmentService.downloadCourseFile(FORMATION_ID, FILE_ID);

            assertThat(result).isNotNull();
            assertThat(result.key()).isEqualTo(courseFile.getStorageKey());
            assertThat(result.contentType()).isEqualTo("application/pdf");
        }

        /**
         * Verifies downloading a course file succeeds when caller is the authoring instructor.
         */
        @Test
        @DisplayName("downloadCourseFile_whenAuthor_shouldReturnStorageResource")
        void downloadCourseFile_whenAuthor_shouldReturnStorageResource() {
            authenticateArtisan(instructorArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull(FILE_ID, FORMATION_ID))
                    .thenReturn(Optional.of(courseFile));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, INSTRUCTOR_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);

            StorageResource mockResource = new StorageResource(
                    courseFile.getStorageKey(),
                    new ByteArrayInputStream("pdf content".getBytes()),
                    courseFile.getContentType(),
                    courseFile.getFileSize(),
                    courseFile.getOriginalFilename()
            );
            when(storageService.load(courseFile.getStorageKey())).thenReturn(mockResource);

            StorageResource result = formationEnrollmentService.downloadCourseFile(FORMATION_ID, FILE_ID);

            assertThat(result).isNotNull();
            assertThat(result.key()).isEqualTo(courseFile.getStorageKey());
        }

        /**
         * Verifies downloading a course file throws 403 Forbidden for non-enrolled artisans.
         */
        @Test
        @DisplayName("downloadCourseFile_whenNonEnrolledArtisan_shouldThrow403Forbidden")
        void downloadCourseFile_whenNonEnrolledArtisan_shouldThrow403Forbidden() {
            authenticateArtisan(peerArtisan);

            when(formationRepository.findByIdAndDeletedAtIsNull(FORMATION_ID))
                    .thenReturn(Optional.of(publishedFormation));
            when(formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull(FILE_ID, FORMATION_ID))
                    .thenReturn(Optional.of(courseFile));
            when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(FORMATION_ID, PEER_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(false);

            assertThatThrownBy(() -> formationEnrollmentService.downloadCourseFile(FORMATION_ID, FILE_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Course syllabus files are available only to confirmed enrolled participants.");

            verify(storageService, never()).load(any());
        }

        /**
         * Verifies retrieving artisan enrollment history maps to FormationEnrollmentDetailDTO.
         */
        @Test
        @DisplayName("getMyEnrollments_shouldReturnPaginatedDetails")
        void getMyEnrollments_shouldReturnPaginatedDetails() {
            authenticateArtisan(peerArtisan);

            FormationEnrollment enrollment = FormationEnrollment.builder()
                    .formation(publishedFormation)
                    .artisan(peerArtisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now())
                    .build();
            enrollment.setId("enrollment-history-1");

            Page<FormationEnrollment> page = new PageImpl<>(List.of(enrollment));
            when(formationEnrollmentRepository.findByArtisanIdAndDeletedAtIsNull(eq(PEER_ID), any(Pageable.class)))
                    .thenReturn(page);
            when(formationEnrollmentRepository.countByFormationIdAndStatus(FORMATION_ID, EnrollmentStatus.CONFIRMED))
                    .thenReturn(3L);

            Page<FormationEnrollmentDetailDTO> result = formationEnrollmentService.getMyEnrollments(PageRequest.of(0, 10));

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getEnrollment().getId()).isEqualTo("enrollment-history-1");
            assertThat(result.getContent().get(0).getFormation().getId()).isEqualTo(FORMATION_ID);
        }

        @Test
        void getMyEnrollments_usesDefaultAndSortedPageableVariants() {
            authenticateArtisan(peerArtisan);
            when(formationEnrollmentRepository.findByArtisanIdAndDeletedAtIsNull(eq(PEER_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());
            formationEnrollmentService.getMyEnrollments(null);
            formationEnrollmentService.getMyEnrollments(PageRequest.of(0, 10, Sort.by("title")));
            verify(formationEnrollmentRepository, Mockito.times(2))
                    .findByArtisanIdAndDeletedAtIsNull(eq(PEER_ID), any(Pageable.class));
        }
    }
}
