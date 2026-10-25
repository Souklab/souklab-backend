package com.project.souklab.service.formation;
import com.project.souklab.security.Permission;

import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.FormationReviewRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationReviewRequestDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationReview;
import com.project.souklab.model.FormationReviewDecision;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.security.AccessControlService;
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

import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link AdminFormationService} administrative review queue,
 * moderation decisions, notification dispatches, and publication transitions.
 */
@ExtendWith(MockitoExtension.class)
class AdminFormationServiceTest {

    private static final String ADMIN_EMAIL = "admin@souklab.com";
    private static final String ADMIN_ID = "admin-uuid-1";

    @Mock
    private FormationRepository formationRepository;

    @Mock
    private FormationReviewRepository formationReviewRepository;

    @Mock
    private FormationFileRepository formationFileRepository;

    @Mock
    private FormationEnrollmentRepository formationEnrollmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AccessControlService accessControlService;

    @Spy
    private AppStorageProperties appStorageProperties = new AppStorageProperties();

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private AdminFormationService adminFormationService;

    private User adminUser;
    private User authorUser;
    private Artisan authorArtisan;
    private Formation pendingFormation;

    @BeforeEach
    void setUp() {
        appStorageProperties.setFileServingPrefix("/api/v1/files/");
        lenient().when(accessControlService.canManageFormations(any())).thenReturn(true);
        adminUser = User.builder()
                .email(ADMIN_EMAIL)
                .firstName("Super")
                .lastName("Admin")
                .build();
        adminUser.setId(ADMIN_ID);

        authorUser = User.builder()
                .email("instructor@souklab.com")
                .firstName("Maitre")
                .lastName("Artisan")
                .build();
        authorUser.setId("author-user-uuid");

        authorArtisan = Artisan.builder()
                .id("author-artisan-uuid")
                .user(authorUser)
                .city("Ghardaia")
                .isTeacher(true)
                .build();

        pendingFormation = Formation.builder()
                .author(authorArtisan)
                .title("Traditional Carpet Weaving")
                .description("Masterclass description")
                .status(FormationStatus.PENDING_REVIEW)
                .price(15000)
                .currency("DZD")
                .durationHours(5)
                .maxParticipants(12)
                .scheduledAt(LocalDateTime.now().plusDays(15))
                .build();
        pendingFormation.setId("formation-uuid-50");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Configures the security context with an authenticated administrator.
     */
    private void authenticateAdmin() {
        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        Authentication authentication = Mockito.mock(Authentication.class);

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(ADMIN_EMAIL);

        GrantedAuthority authority = Permission.Admin.FORMATIONS;
        lenient().doReturn(List.of(authority)).when(authentication).getAuthorities();

        SecurityContextHolder.setContext(securityContext);
        lenient().when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUser));
    }

    @Nested
    @DisplayName("Pending Queue Tests")
    class PendingQueueTests {

        /**
         * Verifies retrieval of formations awaiting moderation.
         */
        @Test
        @DisplayName("getPendingFormations_shouldReturnPaginatedPendingFormations")
        void getPendingFormations_shouldReturnPaginatedPendingFormations() {
            Pageable pageable = PageRequest.of(0, 10);
            when(formationRepository.findByStatusAndDeletedAtIsNull(FormationStatus.PENDING_REVIEW, pageable))
                    .thenReturn(new PageImpl<>(List.of(pendingFormation)));
            when(formationEnrollmentRepository.countByFormationIdAndStatus(pendingFormation.getId(), EnrollmentStatus.CONFIRMED))
                    .thenReturn(0L);

            PaginatedResponse<FormationSummaryDTO> response = adminFormationService.getPendingFormations(pageable);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getId()).isEqualTo("formation-uuid-50");
            assertThat(response.getContent().get(0).getStatus()).isEqualTo(FormationStatus.PENDING_REVIEW);
        }
    }

    @Nested
    @DisplayName("Review Formation Tests")
    class ReviewFormationTests {

        /**
         * Verifies approving a formation updates status to APPROVED, creates review record, and notifies author.
         */
        @Test
        @DisplayName("reviewFormation_whenApproved_shouldUpdateStatusAndNotifyAuthor")
        void reviewFormation_whenApproved_shouldUpdateStatusAndNotifyAuthor() {
            authenticateAdmin();

            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(pendingFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(pendingFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(pendingFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationReviewRequestDTO dto = FormationReviewRequestDTO.builder()
                    .decision(FormationReviewDecision.APPROVED)
                    .comment("Syllabus and curriculum fully verified.")
                    .build();

            FormationResponseDTO response = adminFormationService.reviewFormation(pendingFormation.getId(), dto);

            assertThat(response.getStatus()).isEqualTo(FormationStatus.APPROVED);
            verify(formationReviewRepository).save(any(FormationReview.class));
            verify(notificationService).createForUser(
                    eq(authorUser),
                    eq("Your formation 'Traditional Carpet Weaving' has been approved!"),
                    eq(NotificationType.Formation.APPROVED),
                    eq("formation-uuid-50")
            );
        }

        /**
         * Verifies rejecting a formation with comment updates status to REJECTED and notifies author.
         */
        @Test
        @DisplayName("reviewFormation_whenRejectedWithComment_shouldUpdateStatusAndNotifyAuthor")
        void reviewFormation_whenRejectedWithComment_shouldUpdateStatusAndNotifyAuthor() {
            authenticateAdmin();

            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(pendingFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(pendingFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(pendingFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationReviewRequestDTO dto = FormationReviewRequestDTO.builder()
                    .decision(FormationReviewDecision.REJECTED)
                    .comment("Please clarify workshop location and safety gear.")
                    .build();

            FormationResponseDTO response = adminFormationService.reviewFormation(pendingFormation.getId(), dto);

            assertThat(response.getStatus()).isEqualTo(FormationStatus.REJECTED);
            verify(formationReviewRepository).save(any(FormationReview.class));
            verify(notificationService).createForUser(
                    eq(authorUser),
                    eq("Your formation 'Traditional Carpet Weaving' was rejected: Please clarify workshop location and safety gear."),
                    eq(NotificationType.Formation.REJECTED),
                    eq("formation-uuid-50")
            );
        }

        /**
         * Verifies rejecting a formation without comment throws BadRequestException.
         */
        @Test
        @DisplayName("reviewFormation_whenRejectedWithoutComment_shouldThrowBadRequestException")
        void reviewFormation_whenRejectedWithoutComment_shouldThrowBadRequestException() {
            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));

            FormationReviewRequestDTO dto = FormationReviewRequestDTO.builder()
                    .decision(FormationReviewDecision.REJECTED)
                    .comment("")
                    .build();

            assertThatThrownBy(() -> adminFormationService.reviewFormation(pendingFormation.getId(), dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Review comment is required when rejecting a formation.");
        }

        /**
         * Verifies reviewing a formation that is not PENDING_REVIEW throws ConflictException.
         */
        @Test
        @DisplayName("reviewFormation_whenNotPendingReview_shouldThrowConflictException")
        void reviewFormation_whenNotPendingReview_shouldThrowConflictException() {
            pendingFormation.setStatus(FormationStatus.DRAFT);
            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));

            FormationReviewRequestDTO dto = FormationReviewRequestDTO.builder()
                    .decision(FormationReviewDecision.APPROVED)
                    .build();

            assertThatThrownBy(() -> adminFormationService.reviewFormation(pendingFormation.getId(), dto))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Formation is not pending review");
        }
    }

    @Nested
    @DisplayName("Publish Formation Tests")
    class PublishFormationTests {

        /**
         * Verifies publishing an APPROVED formation updates status to PUBLISHED.
         */
        @Test
        @DisplayName("publishFormation_whenApproved_shouldUpdateStatusToPublished")
        void publishFormation_whenApproved_shouldUpdateStatusToPublished() {
            pendingFormation.setStatus(FormationStatus.APPROVED);

            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));
            when(formationRepository.save(any(Formation.class))).thenAnswer(inv -> inv.getArgument(0));
            when(formationFileRepository.findByFormationIdAndDeletedAtIsNull(pendingFormation.getId())).thenReturn(List.of());
            when(formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(pendingFormation.getId())).thenReturn(List.of());
            when(formationEnrollmentRepository.countByFormationIdAndStatus(pendingFormation.getId(), EnrollmentStatus.CONFIRMED)).thenReturn(0L);

            FormationResponseDTO response = adminFormationService.publishFormation(pendingFormation.getId());

            assertThat(response.getStatus()).isEqualTo(FormationStatus.PUBLISHED);
            verify(formationRepository).save(pendingFormation);
        }

        /**
         * Verifies publishing a formation that is not APPROVED throws ConflictException.
         */
        @Test
        @DisplayName("publishFormation_whenNotApproved_shouldThrowConflictException")
        void publishFormation_whenNotApproved_shouldThrowConflictException() {
            pendingFormation.setStatus(FormationStatus.PENDING_REVIEW);

            when(formationRepository.findByIdAndDeletedAtIsNull(pendingFormation.getId()))
                    .thenReturn(Optional.of(pendingFormation));

            assertThatThrownBy(() -> adminFormationService.publishFormation(pendingFormation.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Only approved formations can be published");
        }
    }
}
