package com.project.souklab.service.formateur;
import com.project.souklab.security.Permission;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formateur.FormateurApproveDTO;
import com.project.souklab.dto.formateur.FormateurCooldownOverrideDTO;
import com.project.souklab.dto.formateur.FormateurGrantDTO;
import com.project.souklab.dto.formateur.FormateurRejectDTO;
import com.project.souklab.dto.formateur.FormateurRequestDTO;
import com.project.souklab.dto.formateur.FormateurRequestResponseDTO;
import com.project.souklab.dto.formateur.FormateurRevokeDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanFormateurRequest;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.util.EmailUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link ArtisanFormateurService}.
 * Covers request lifecycle (submit, approve, reject), administrative direct grant/revoke,
 * cooldown overrides, paginated requests retrieval, and DTO transformation branches.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanFormateurServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T12:00:00Z");
    private static final ZoneOffset ZONE = ZoneOffset.UTC;

    @Mock
    private ArtisanFormateurRequestRepository formateurRequestRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private EmailUtil emailUtil;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    private Clock clock;
    private ArtisanFormateurService artisanFormateurService;

    private User artisanUser;
    private Artisan artisanProfile;
    private User adminUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZONE);
        ArtisanProperties properties = new ArtisanProperties();
        properties.getFormateur().setReapplyCooldownDays(14);
        artisanFormateurService = new ArtisanFormateurService(
                formateurRequestRepository,
                artisanRepository,
                userRepository,
                notificationService,
                emailUtil,
                properties,
                clock
        );

        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.isAuthenticated()).thenReturn(true);
        lenient().when(authentication.getName()).thenReturn("artisan@example.com");
        SecurityContextHolder.setContext(securityContext);

        artisanUser = User.builder()
                .email("artisan@example.com")
                .firstName("Karim")
                .lastName("Bensaid")
                .status(AccountStatus.ACTIVE)
                .build();
        artisanUser.setId("artisan-user-1");

        artisanProfile = Artisan.builder()
                .id("artisan-user-1")
                .user(artisanUser)
                .isTeacher(false)
                .build();

        adminUser = User.builder()
                .email("admin@example.com")
                .firstName("Admin")
                .lastName("Super")
                .status(AccountStatus.ACTIVE)
                .build();
        adminUser.setId("admin-user-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Verifies submitRequest throws ResourceNotFoundException when current authenticated user is not in repository.
     */
    @Test
    @DisplayName("submitRequest: throws ResourceNotFoundException when authenticated user is not found")
    void submitRequest_whenUserNotFound_shouldThrowResourceNotFoundException() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.empty());

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: artisan@example.com");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest throws ForbiddenException when user status is not ACTIVE.
     */
    @Test
    @DisplayName("submitRequest: throws ForbiddenException when user account is not ACTIVE")
    void submitRequest_whenUserNotActive_shouldThrowForbiddenException() {
        artisanUser.setStatus(AccountStatus.PENDING);
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Your account must be ACTIVE to submit a Formateur request. Current status: PENDING");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest throws ForbiddenException when no Artisan profile exists for the user.
     */
    @Test
    @DisplayName("submitRequest: throws ForbiddenException when user does not have an Artisan profile")
    void submitRequest_whenArtisanProfileNotFound_shouldThrowForbiddenException() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.empty());

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only registered artisans can request Formateur status.");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest throws ConflictException when artisan is already an approved Formateur (isTeacher is true).
     */
    @Test
    @DisplayName("submitRequest: throws ConflictException when artisan is already an approved Formateur")
    void submitRequest_whenArtisanAlreadyTeacher_shouldThrowConflictException() {
        artisanProfile.setTeacher(true);
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("You are already an approved Formateur.");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest throws ConflictException when artisan has an existing PENDING request.
     */
    @Test
    @DisplayName("submitRequest: throws ConflictException when a PENDING request already exists")
    void submitRequest_whenPendingRequestAlreadyExists_shouldThrowConflictException() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(true);

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("You already have a pending Formateur request.");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest throws ForbiddenException when latest request has canReapply=false (permanent block).
     */
    @Test
    @DisplayName("submitRequest: throws ForbiddenException when artisan is permanently blocked from reapplying")
    void submitRequest_whenLatestRequestHasCanReapplyFalse_shouldThrowForbiddenException() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);

        ArtisanFormateurRequest blockedRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(false)
                .build();
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(blockedRequest));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Pottery").build();

        assertThatThrownBy(() -> artisanFormateurService.submitRequest(dto))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are permanently blocked from submitting new Formateur requests.");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies submitRequest succeeds when latest request has canReapply=true and cooldownUntil=null.
     */
    @Test
    @DisplayName("submitRequest: succeeds when latest request allows reapplication and cooldown is null")
    void submitRequest_whenLatestRequestHasNullCooldown_shouldSucceed() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);

        ArtisanFormateurRequest pastRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(true)
                .cooldownUntil(null)
                .build();
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(pastRequest));

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("Ceramics teaching")
                .canReapply(true)
                .build();
        saved.setId("saved-req-1");
        when(formateurRequestRepository.saveAndFlush(any(ArtisanFormateurRequest.class))).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("Ceramics teaching").build();
        FormateurRequestResponseDTO response = artisanFormateurService.submitRequest(dto);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("saved-req-1");
        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.PENDING);
        assertThat(response.getMotivation()).isEqualTo("Ceramics teaching");
    }

    /**
     * Verifies submitRequest formats notification message properly when motivation is blank string.
     */
    @Test
    @DisplayName("submitRequest: formats notification without motivation quotation when motivation is blank")
    void submitRequest_whenMotivationIsBlank_shouldFormatNotificationWithoutQuotation() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("   ")
                .canReapply(true)
                .build();
        saved.setId("saved-req-blank");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation("   ").build();
        artisanFormateurService.submitRequest(dto);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(adminUser), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("saved-req-blank"));
        assertThat(notifCaptor.getValue()).isEqualTo("New artisan formateur request submitted by Karim Bensaid (artisan@example.com)");
    }

    /**
     * Verifies submitRequest formats notification properly when DTO is non-null but motivation is null.
     */
    @Test
    @DisplayName("submitRequest: formats notification without motivation quotation when DTO motivation is null")
    void submitRequest_whenDtoHasNullMotivation_shouldFormatNotificationWithoutQuotation() {
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation(null)
                .canReapply(true)
                .build();
        saved.setId("saved-req-null-mot");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        FormateurRequestDTO dto = FormateurRequestDTO.builder().motivation(null).build();
        artisanFormateurService.submitRequest(dto);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(adminUser), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("saved-req-null-mot"));
        assertThat(notifCaptor.getValue()).isEqualTo("New artisan formateur request submitted by Karim Bensaid (artisan@example.com)");
    }

    /**
     * Verifies submitRequest formats notification properly when artisan has only firstName.
     */
    @Test
    @DisplayName("submitRequest: formats notification with single name when only firstName is present")
    void submitRequest_whenArtisanHasOnlyFirstName_shouldFormatNotificationCorrectly() {
        artisanUser.setFirstName("Karim");
        artisanUser.setLastName(null);

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .build();
        saved.setId("req-single-name");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        artisanFormateurService.submitRequest(null);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(adminUser), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("req-single-name"));
        assertThat(notifCaptor.getValue()).isEqualTo("New artisan formateur request submitted by Karim (artisan@example.com)");
    }

    /**
     * Verifies submitRequest formats notification properly when artisan has only lastName.
     */
    @Test
    @DisplayName("submitRequest: formats notification with single name when only lastName is present")
    void submitRequest_whenArtisanHasOnlyLastName_shouldFormatNotificationCorrectly() {
        artisanUser.setFirstName(null);
        artisanUser.setLastName("Bensaid");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .build();
        saved.setId("req-single-last");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        artisanFormateurService.submitRequest(null);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(adminUser), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("req-single-last"));
        assertThat(notifCaptor.getValue()).isEqualTo("New artisan formateur request submitted by Bensaid (artisan@example.com)");
    }

    /**
     * Verifies getPendingRequests queries repository by PENDING status ordered by createdAt desc and maps to DTOs.
     */
    @Test
    @DisplayName("getPendingRequests: returns paginated list of pending requests mapped to DTOs")
    void getPendingRequests_shouldQueryPendingRequestsOrderedByCreatedAtDescAndMapToDTO() {
        Pageable pageable = PageRequest.of(0, 10);
        ArtisanFormateurRequest req = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("Wood carving")
                .canReapply(true)
                .build();
        req.setId("pending-req-1");
        Page<ArtisanFormateurRequest> page = new PageImpl<>(List.of(req), pageable, 1);

        when(formateurRequestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(FormateurRequestStatus.PENDING, pageable))
                .thenReturn(page);

        PaginatedResponse<FormateurRequestResponseDTO> response = artisanFormateurService.getPendingRequests(pageable);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        FormateurRequestResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getId()).isEqualTo("pending-req-1");
        assertThat(dto.getArtisanId()).isEqualTo("artisan-user-1");
        assertThat(dto.getArtisanName()).isEqualTo("Karim Bensaid");
        assertThat(dto.getArtisanEmail()).isEqualTo("artisan@example.com");
        assertThat(dto.getStatus()).isEqualTo(FormateurRequestStatus.PENDING);
    }

    /**
     * Verifies approveRequest throws ResourceNotFoundException when admin user is not found.
     */
    @Test
    @DisplayName("approveRequest: throws ResourceNotFoundException when acting admin is not found")
    void approveRequest_whenAdminUserNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        FormateurApproveDTO dto = new FormateurApproveDTO();
        dto.setAdminNote("Approved");

        assertThatThrownBy(() -> artisanFormateurService.approveRequest("req-1", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Admin not found: admin@example.com");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies approveRequest throws ResourceNotFoundException when request id is not found.
     */
    @Test
    @DisplayName("approveRequest: throws ResourceNotFoundException when request does not exist")
    void approveRequest_whenRequestNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("missing-req")).thenReturn(Optional.empty());

        FormateurApproveDTO dto = new FormateurApproveDTO();
        dto.setAdminNote("Approved");

        assertThatThrownBy(() -> artisanFormateurService.approveRequest("missing-req", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Formateur request not found with id: missing-req");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies approveRequest throws BadRequestException when request status is already APPROVED.
     */
    @Test
    @DisplayName("approveRequest: throws BadRequestException when request is already APPROVED")
    void approveRequest_whenRequestStatusAlreadyApproved_shouldThrowBadRequestException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.APPROVED)
                .build();
        request.setId("req-approved");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-approved")).thenReturn(Optional.of(request));

        FormateurApproveDTO dto = new FormateurApproveDTO();
        dto.setAdminNote("Approved");

        assertThatThrownBy(() -> artisanFormateurService.approveRequest("req-approved", dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Request is already APPROVED.");

        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies approveRequest successfully sets status APPROVED, teacher true, records decision metadata, and dual-dispatches.
     */
    @Test
    @DisplayName("approveRequest: approves pending request, sets isTeacher true, and dual-dispatches notification and email")
    void approveRequest_whenValidPendingRequest_shouldApproveAndSetTeacherAndDualDispatch() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("I want to teach calligraphy")
                .canReapply(true)
                .build();
        request.setId("req-pending-approve");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-pending-approve")).thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FormateurApproveDTO dto = new FormateurApproveDTO();
        dto.setAdminNote("Portfolio validated, exemplary work.");

        FormateurRequestResponseDTO response = artisanFormateurService.approveRequest("req-pending-approve", dto);

        LocalDateTime expectedDecidedAt = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.APPROVED);
        assertThat(response.getAdminNote()).isEqualTo("Portfolio validated, exemplary work.");
        assertThat(response.getDecidedByAdminId()).isEqualTo("admin-user-1");
        assertThat(response.getDecidedByAdminEmail()).isEqualTo("admin@example.com");
        assertThat(response.getDecidedAt()).isEqualTo(expectedDecidedAt);

        assertThat(artisanProfile.isTeacher()).isTrue();
        verify(artisanRepository).save(artisanProfile);
        verify(formateurRequestRepository).saveAndFlush(request);

        verify(notificationService).createForUser(
                artisanUser,
                "Your request for Formateur status has been approved! Note: Portfolio validated, exemplary work.",
                NotificationType.Formateur.APPROVED,
                "req-pending-approve"
        );
        verify(emailUtil).sendFormateurApprovedEmail("artisan@example.com", "Portfolio validated, exemplary work.");
    }

    /**
     * Verifies rejectRequest throws ResourceNotFoundException when admin user is not found.
     */
    @Test
    @DisplayName("rejectRequest: throws ResourceNotFoundException when acting admin is not found")
    void rejectRequest_whenAdminUserNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Rejected");

        assertThatThrownBy(() -> artisanFormateurService.rejectRequest("req-1", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Admin not found: admin@example.com");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies rejectRequest throws ResourceNotFoundException when request id is not found.
     */
    @Test
    @DisplayName("rejectRequest: throws ResourceNotFoundException when request does not exist")
    void rejectRequest_whenRequestNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-missing")).thenReturn(Optional.empty());

        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Rejected");

        assertThatThrownBy(() -> artisanFormateurService.rejectRequest("req-missing", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Formateur request not found with id: req-missing");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies rejectRequest throws BadRequestException when request status is already REJECTED.
     */
    @Test
    @DisplayName("rejectRequest: throws BadRequestException when request is already REJECTED")
    void rejectRequest_whenRequestStatusAlreadyRejected_shouldThrowBadRequestException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .build();
        request.setId("req-rejected");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-rejected")).thenReturn(Optional.of(request));

        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Rejected");

        assertThatThrownBy(() -> artisanFormateurService.rejectRequest("req-rejected", dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Request is already REJECTED.");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies rejectRequest when canReapply is false sets canReapply=false, cooldownUntil=null, and dual-dispatches.
     */
    @Test
    @DisplayName("rejectRequest: sets canReapply false and cooldown null when permanently blocking reapplication")
    void rejectRequest_whenCanReapplyFalse_shouldPermanentlyBlockAndSetCooldownNull() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("Teaching proposal")
                .canReapply(true)
                .build();
        request.setId("req-reject-perm");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-reject-perm")).thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Severe policy breach in submission");
        dto.setCanReapply(false);
        dto.setCooldownUntil(null);

        FormateurRequestResponseDTO response = artisanFormateurService.rejectRequest("req-reject-perm", dto);

        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.REJECTED);
        assertThat(response.isCanReapply()).isFalse();
        assertThat(response.getCooldownUntil()).isNull();
        assertThat(request.isCanReapply()).isFalse();
        assertThat(request.getCooldownUntil()).isNull();

        verify(notificationService).createForUser(
                artisanUser,
                "Your Formateur request was rejected. Note: Severe policy breach in submission",
                NotificationType.Formateur.REJECTED,
                "req-reject-perm"
        );
        verify(emailUtil).sendFormateurRejectedEmail("artisan@example.com", "Severe policy breach in submission", null, false);
    }

    /**
     * Verifies rejectRequest when canReapply is true and explicit cooldownUntil is provided preserves custom cooldown.
     */
    @Test
    @DisplayName("rejectRequest: uses custom cooldown timestamp when canReapply is true and timestamp is provided")
    void rejectRequest_whenCanReapplyTrueWithCustomCooldown_shouldUseCustomCooldown() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .build();
        request.setId("req-reject-custom");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-reject-custom")).thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime customCooldown = LocalDateTime.of(2026, 10, 1, 0, 0);
        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Please re-apply after acquiring workshop credentials");
        dto.setCanReapply(true);
        dto.setCooldownUntil(customCooldown);

        FormateurRequestResponseDTO response = artisanFormateurService.rejectRequest("req-reject-custom", dto);

        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.REJECTED);
        assertThat(response.isCanReapply()).isTrue();
        assertThat(response.getCooldownUntil()).isEqualTo(customCooldown);
        assertThat(request.getCooldownUntil()).isEqualTo(customCooldown);

        verify(emailUtil).sendFormateurRejectedEmail("artisan@example.com", "Please re-apply after acquiring workshop credentials", customCooldown, true);
    }

    /**
     * Verifies rejectRequest when canReapply is null defaults canReapply to true and sets 14 days cooldown.
     */
    @Test
    @DisplayName("rejectRequest: defaults canReapply to true and sets 14 days cooldown when canReapply is null")
    void rejectRequest_whenCanReapplyNull_shouldDefaultCanReapplyToTrueAnd14DaysCooldown() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .build();
        request.setId("req-reject-null-reapply");
        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-reject-null-reapply")).thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FormateurRejectDTO dto = new FormateurRejectDTO();
        dto.setAdminNote("Try again later");
        dto.setCanReapply(null);
        dto.setCooldownUntil(null);

        FormateurRequestResponseDTO response = artisanFormateurService.rejectRequest("req-reject-null-reapply", dto);

        LocalDateTime expectedCooldown = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusDays(14);

        assertThat(response.isCanReapply()).isTrue();
        assertThat(response.getCooldownUntil()).isEqualTo(expectedCooldown);
        assertThat(request.isCanReapply()).isTrue();
        assertThat(request.getCooldownUntil()).isEqualTo(expectedCooldown);
    }

    /**
     * Verifies grantDirectly throws ResourceNotFoundException when admin is not found.
     */
    @Test
    @DisplayName("grantDirectly: throws ResourceNotFoundException when acting admin is not found")
    void grantDirectly_whenAdminUserNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        FormateurGrantDTO dto = new FormateurGrantDTO();
        dto.setAdminNote("Granted");

        assertThatThrownBy(() -> artisanFormateurService.grantDirectly("artisan-user-1", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Admin not found: admin@example.com");

        verify(artisanRepository, never()).save(any());
        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies grantDirectly throws ResourceNotFoundException when artisan profile is not found.
     */
    @Test
    @DisplayName("grantDirectly: throws ResourceNotFoundException when artisan profile does not exist")
    void grantDirectly_whenArtisanNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("missing-artisan")).thenReturn(Optional.empty());

        FormateurGrantDTO dto = new FormateurGrantDTO();
        dto.setAdminNote("Granted");

        assertThatThrownBy(() -> artisanFormateurService.grantDirectly("missing-artisan", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: missing-artisan");

        verify(artisanRepository, never()).save(any());
        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies grantDirectly throws BadRequestException when artisan is already an approved Formateur.
     */
    @Test
    @DisplayName("grantDirectly: throws BadRequestException when artisan already has teacher status")
    void grantDirectly_whenArtisanAlreadyTeacher_shouldThrowBadRequestException() {
        artisanProfile.setTeacher(true);
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        FormateurGrantDTO dto = new FormateurGrantDTO();
        dto.setAdminNote("Granted");

        assertThatThrownBy(() -> artisanFormateurService.grantDirectly("artisan-user-1", dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Artisan is already an approved Formateur.");

        verify(artisanRepository, never()).save(any());
        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies grantDirectly sets isTeacher true, creates audit record, dual-dispatches, and returns mapped DTO.
     */
    @Test
    @DisplayName("grantDirectly: directly grants formateur status, records audit record, and dual-dispatches")
    void grantDirectly_whenValidArtisan_shouldSetTeacherTrueAndCreateAuditRecordAndDualDispatch() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ArtisanFormateurRequest saved = inv.getArgument(0);
            saved.setId("audit-grant-1");
            return saved;
        });

        FormateurGrantDTO dto = new FormateurGrantDTO();
        dto.setAdminNote("Master artisan with 20 years experience");

        FormateurRequestResponseDTO response = artisanFormateurService.grantDirectly("artisan-user-1", dto);

        LocalDateTime expectedDecidedAt = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("audit-grant-1");
        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.APPROVED);
        assertThat(response.getAdminNote()).isEqualTo("Master artisan with 20 years experience");
        assertThat(response.getDecidedByAdminId()).isEqualTo("admin-user-1");
        assertThat(response.getDecidedByAdminEmail()).isEqualTo("admin@example.com");
        assertThat(response.getDecidedAt()).isEqualTo(expectedDecidedAt);

        assertThat(artisanProfile.isTeacher()).isTrue();
        verify(artisanRepository).save(artisanProfile);

        ArgumentCaptor<ArtisanFormateurRequest> auditCaptor = ArgumentCaptor.forClass(ArtisanFormateurRequest.class);
        verify(formateurRequestRepository).saveAndFlush(auditCaptor.capture());
        ArtisanFormateurRequest audit = auditCaptor.getValue();
        assertThat(audit.getArtisan()).isEqualTo(artisanProfile);
        assertThat(audit.getStatus()).isEqualTo(FormateurRequestStatus.APPROVED);
        assertThat(audit.getMotivation()).isNull();
        assertThat(audit.getAdminNote()).isEqualTo("Master artisan with 20 years experience");
        assertThat(audit.isCanReapply()).isTrue();
        assertThat(audit.getCooldownUntil()).isNull();
        assertThat(audit.getDecidedBy()).isEqualTo(adminUser);
        assertThat(audit.getDecidedAt()).isEqualTo(expectedDecidedAt);

        verify(notificationService).createForUser(
                artisanUser,
                "You have been granted Formateur status by an administrator! Note: Master artisan with 20 years experience",
                NotificationType.Formateur.GRANTED,
                "audit-grant-1"
        );
        verify(emailUtil).sendFormateurGrantedEmail("artisan@example.com", "Master artisan with 20 years experience");
    }

    /**
     * Verifies revokeDirectly throws ResourceNotFoundException when admin is not found.
     */
    @Test
    @DisplayName("revokeDirectly: throws ResourceNotFoundException when acting admin is not found")
    void revokeDirectly_whenAdminUserNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        FormateurRevokeDTO dto = new FormateurRevokeDTO();
        dto.setReason("Revoked");

        assertThatThrownBy(() -> artisanFormateurService.revokeDirectly("artisan-user-1", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Admin not found: admin@example.com");

        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies revokeDirectly throws ResourceNotFoundException when artisan is not found.
     */
    @Test
    @DisplayName("revokeDirectly: throws ResourceNotFoundException when artisan does not exist")
    void revokeDirectly_whenArtisanNotFound_shouldThrowResourceNotFoundException() {
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("missing-artisan")).thenReturn(Optional.empty());

        FormateurRevokeDTO dto = new FormateurRevokeDTO();
        dto.setReason("Revoked");

        assertThatThrownBy(() -> artisanFormateurService.revokeDirectly("missing-artisan", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: missing-artisan");

        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies revokeDirectly throws BadRequestException when artisan is not currently an approved Formateur.
     */
    @Test
    @DisplayName("revokeDirectly: throws BadRequestException when artisan is not currently a Formateur")
    void revokeDirectly_whenArtisanNotTeacher_shouldThrowBadRequestException() {
        artisanProfile.setTeacher(false);
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        FormateurRevokeDTO dto = new FormateurRevokeDTO();
        dto.setReason("Inactivity");

        assertThatThrownBy(() -> artisanFormateurService.revokeDirectly("artisan-user-1", dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Artisan is not currently an approved Formateur.");

        verify(artisanRepository, never()).save(any());
    }

    /**
     * Verifies revokeDirectly sets isTeacher false, dual-dispatches notification and email.
     */
    @Test
    @DisplayName("revokeDirectly: removes Formateur status and dual-dispatches notification and email")
    void revokeDirectly_whenValidTeacher_shouldSetTeacherFalseAndDualDispatch() {
        artisanProfile.setTeacher(true);
        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        FormateurRevokeDTO dto = new FormateurRevokeDTO();
        dto.setReason("Repeated workshop cancellations without notice");

        artisanFormateurService.revokeDirectly("artisan-user-1", dto);

        assertThat(artisanProfile.isTeacher()).isFalse();
        verify(artisanRepository).save(artisanProfile);

        verify(notificationService).createForUser(
                artisanUser,
                "Your Formateur status has been revoked. Reason: Repeated workshop cancellations without notice",
                NotificationType.Formateur.REVOKED,
                "artisan-user-1"
        );
        verify(emailUtil).sendFormateurRevokedEmail("artisan@example.com", "Repeated workshop cancellations without notice");
    }

    /**
     * Verifies liftCooldown throws ResourceNotFoundException when artisan is not found.
     */
    @Test
    @DisplayName("liftCooldown: throws ResourceNotFoundException when artisan profile does not exist")
    void liftCooldown_whenArtisanNotFound_shouldThrowResourceNotFoundException() {
        when(artisanRepository.findById("missing-artisan")).thenReturn(Optional.empty());

        FormateurCooldownOverrideDTO dto = new FormateurCooldownOverrideDTO();

        assertThatThrownBy(() -> artisanFormateurService.liftCooldown("missing-artisan", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: missing-artisan");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies liftCooldown throws ResourceNotFoundException when no prior request record exists for artisan.
     */
    @Test
    @DisplayName("liftCooldown: throws ResourceNotFoundException when artisan has no request record")
    void liftCooldown_whenNoRequestRecordFound_shouldThrowResourceNotFoundException() {
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.empty());

        FormateurCooldownOverrideDTO dto = new FormateurCooldownOverrideDTO();

        assertThatThrownBy(() -> artisanFormateurService.liftCooldown("artisan-user-1", dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No Formateur request record found for artisan ID: artisan-user-1");

        verify(formateurRequestRepository, never()).saveAndFlush(any());
    }

    /**
     * Verifies liftCooldown clears cooldownUntil when canReapply is true and cooldownUntil is null in DTO.
     */
    @Test
    @DisplayName("liftCooldown: clears cooldown when canReapply is true and cooldownUntil is null")
    void liftCooldown_whenCanReapplyTrueAndCooldownNull_shouldClearCooldown() {
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        LocalDateTime existingCooldown = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusDays(10);
        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(false)
                .cooldownUntil(existingCooldown)
                .build();
        request.setId("req-override-1");

        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FormateurCooldownOverrideDTO dto = new FormateurCooldownOverrideDTO();
        dto.setCanReapply(true);
        dto.setCooldownUntil(null);

        FormateurRequestResponseDTO response = artisanFormateurService.liftCooldown("artisan-user-1", dto);

        assertThat(response.isCanReapply()).isTrue();
        assertThat(response.getCooldownUntil()).isNull();
        assertThat(request.isCanReapply()).isTrue();
        assertThat(request.getCooldownUntil()).isNull();
    }

    /**
     * Verifies liftCooldown updates cooldownUntil when explicit timestamp is provided.
     */
    @Test
    @DisplayName("liftCooldown: updates cooldown timestamp when explicit cooldownUntil is provided")
    void liftCooldown_whenExplicitCooldownProvided_shouldUpdateCooldownUntil() {
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        LocalDateTime existingCooldown = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusDays(14);
        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(true)
                .cooldownUntil(existingCooldown)
                .build();
        request.setId("req-override-2");

        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime newCooldown = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE).plusDays(3);
        FormateurCooldownOverrideDTO dto = new FormateurCooldownOverrideDTO();
        dto.setCooldownUntil(newCooldown);

        FormateurRequestResponseDTO response = artisanFormateurService.liftCooldown("artisan-user-1", dto);

        assertThat(response.getCooldownUntil()).isEqualTo(newCooldown);
        assertThat(request.getCooldownUntil()).isEqualTo(newCooldown);
    }

    /**
     * Verifies liftCooldown sets canReapply to false when explicitly requested.
     */
    @Test
    @DisplayName("liftCooldown: updates canReapply to false when explicitly set")
    void liftCooldown_whenCanReapplyFalse_shouldUpdateCanReapplyFalse() {
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(true)
                .build();
        request.setId("req-override-3");

        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(request));
        when(formateurRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        FormateurCooldownOverrideDTO dto = new FormateurCooldownOverrideDTO();
        dto.setCanReapply(false);

        FormateurRequestResponseDTO response = artisanFormateurService.liftCooldown("artisan-user-1", dto);

        assertThat(response.isCanReapply()).isFalse();
        assertThat(request.isCanReapply()).isFalse();
    }

    /**
     * Verifies mapToDTO handles request with null artisan safely.
     */
    @Test
    @DisplayName("mapToDTO: maps safely when artisan is null on request")
    void mapToDTO_whenArtisanIsNull_shouldMapSafelyWithNullArtisanFields() {
        Pageable pageable = PageRequest.of(0, 10);
        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(null)
                .status(FormateurRequestStatus.PENDING)
                .motivation("Independent request")
                .build();
        request.setId("req-no-artisan");

        when(formateurRequestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(FormateurRequestStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(request), pageable, 1));

        PaginatedResponse<FormateurRequestResponseDTO> response = artisanFormateurService.getPendingRequests(pageable);

        FormateurRequestResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getArtisanId()).isNull();
        assertThat(dto.getArtisanName()).isNull();
        assertThat(dto.getArtisanEmail()).isNull();
    }

    /**
     * Verifies mapToDTO handles request with null user on artisan profile safely.
     */
    @Test
    @DisplayName("mapToDTO: maps safely when user is null on artisan profile")
    void mapToDTO_whenArtisanUserIsNull_shouldMapSafelyWithNullUserFields() {
        Pageable pageable = PageRequest.of(0, 10);
        Artisan orphanArtisan = Artisan.builder()
                .id("orphan-artisan")
                .user(null)
                .build();
        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(orphanArtisan)
                .status(FormateurRequestStatus.PENDING)
                .build();
        request.setId("req-orphan-artisan");

        when(formateurRequestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(FormateurRequestStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(request), pageable, 1));

        PaginatedResponse<FormateurRequestResponseDTO> response = artisanFormateurService.getPendingRequests(pageable);

        FormateurRequestResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getArtisanId()).isEqualTo("orphan-artisan");
        assertThat(dto.getArtisanName()).isNull();
        assertThat(dto.getArtisanEmail()).isNull();
    }

    /**
     * Verifies mapToDTO handles request with null decidedBy user safely.
     */
    @Test
    @DisplayName("mapToDTO: maps null decidedBy admin ID and email when request has not been decided")
    void mapToDTO_whenDecidedByIsNull_shouldMapSafelyWithNullAdminFields() {
        Pageable pageable = PageRequest.of(0, 10);
        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .decidedBy(null)
                .build();
        request.setId("req-undecided");

        when(formateurRequestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(FormateurRequestStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(request), pageable, 1));

        PaginatedResponse<FormateurRequestResponseDTO> response = artisanFormateurService.getPendingRequests(pageable);

        FormateurRequestResponseDTO dto = response.getContent().get(0);
        assertThat(dto.getDecidedByAdminId()).isNull();
        assertThat(dto.getDecidedByAdminEmail()).isNull();
    }
}
