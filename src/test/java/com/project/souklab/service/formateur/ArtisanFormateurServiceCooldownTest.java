package com.project.souklab.service.formateur;
import com.project.souklab.security.Permission;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.formateur.FormateurRejectDTO;
import com.project.souklab.dto.formateur.FormateurRequestDTO;
import com.project.souklab.dto.formateur.FormateurRequestResponseDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanFormateurRequest;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.util.EmailUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demonstrates deterministic testing of time-dependent logic using an injected {@link Clock}.
 *
 * <p>Validates:
 * <ul>
 *   <li>Rejection sets cooldown period exactly 14 days from fixed clock instant.</li>
 *   <li>Re-application within the 14-day cooldown window is rejected with {@link ForbiddenException}.</li>
 *   <li>Re-application after advancing the fixed clock past the cooldown window succeeds.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ArtisanFormateurServiceCooldownTest {

    private static final Instant REJECTION_INSTANT = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant DURING_COOLDOWN_INSTANT = Instant.parse("2026-09-10T12:00:00Z");

    private static final Instant AFTER_COOLDOWN_INSTANT = Instant.parse("2026-09-16T12:00:00Z");

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

    private User artisanUser;
    private Artisan artisanProfile;
    private User adminUser;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("artisan@example.com");
        SecurityContextHolder.setContext(securityContext);

        artisanUser = User.builder()
                .email("artisan@example.com")
                .firstName("Karim")
                .lastName("Bensaid")
                .status(AccountStatus.ACTIVE)
                .build();
        artisanUser.setId("artisan-user-1");

        artisanProfile = Artisan.builder()
                .id("artisan-profile-1")
                .user(artisanUser)
                .isTeacher(false)
                .build();

        adminUser = User.builder()
                .email("admin@example.com")
                .firstName("Admin")
                .status(AccountStatus.ACTIVE)
                .build();
        adminUser.setId("admin-user-1");
    }

    private ArtisanProperties configuredProperties() {
        ArtisanProperties properties = new ArtisanProperties();
        properties.getFormateur().setReapplyCooldownDays(14);
        return properties;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("rejectRequest: calculates cooldownUntil exactly 14 days from fixed Clock instant")
    void testRejectRequest_setsCooldownDeterministicFromClock() {
        Clock fixedClock = Clock.fixed(REJECTION_INSTANT, ZoneOffset.UTC);
        ArtisanFormateurService service = new ArtisanFormateurService(
                formateurRequestRepository,
                artisanRepository,
                userRepository,
                notificationService,
                emailUtil,
                configuredProperties(),
                fixedClock
        );

        when(authentication.getName()).thenReturn("admin@example.com");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));

        ArtisanFormateurRequest pendingRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("I want to teach traditional pottery.")
                .canReapply(true)
                .build();
        pendingRequest.setId("req-123");

        when(formateurRequestRepository.findByIdAndDeletedAtIsNull("req-123")).thenReturn(Optional.of(pendingRequest));
        when(formateurRequestRepository.saveAndFlush(any(ArtisanFormateurRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        FormateurRejectDTO rejectDTO = new FormateurRejectDTO();
        rejectDTO.setAdminNote("Portfolio lacks required documentation.");
        rejectDTO.setCanReapply(true);
        rejectDTO.setCooldownUntil(null);

        FormateurRequestResponseDTO response = service.rejectRequest("req-123", rejectDTO);

        LocalDateTime expectedDecidedAt = LocalDateTime.ofInstant(REJECTION_INSTANT, ZoneOffset.UTC);
        LocalDateTime expectedCooldown = expectedDecidedAt.plusDays(14);

        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.REJECTED);
        assertThat(pendingRequest.getDecidedAt()).isEqualTo(expectedDecidedAt);
        assertThat(pendingRequest.getCooldownUntil()).isEqualTo(expectedCooldown);
    }

    @Test
    @DisplayName("submitRequest: re-application fails with ForbiddenException during 14-day cooldown")
    void testSubmitRequest_blockedDuringCooldownWindow() {
        Clock fixedClockDuringCooldown = Clock.fixed(DURING_COOLDOWN_INSTANT, ZoneOffset.UTC);
        ArtisanFormateurService service = new ArtisanFormateurService(
                formateurRequestRepository,
                artisanRepository,
                userRepository,
                notificationService,
                emailUtil,
                configuredProperties(),
                fixedClockDuringCooldown
        );

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);

        LocalDateTime rejectionCooldownUntil = LocalDateTime.ofInstant(REJECTION_INSTANT, ZoneOffset.UTC).plusDays(14);
        ArtisanFormateurRequest priorRejectedRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(true)
                .cooldownUntil(rejectionCooldownUntil)
                .build();

        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(priorRejectedRequest));

        FormateurRequestDTO requestDTO = new FormateurRequestDTO();
        requestDTO.setMotivation("Reapplying with improved portfolio.");

        assertThatThrownBy(() -> service.submitRequest(requestDTO))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("You cannot submit a request during the cooldown period")
                .hasMessageContaining(rejectionCooldownUntil.toString());
    }

    @Test
    @DisplayName("submitRequest: re-application succeeds after Clock advances past the 14-day cooldown")
    void testSubmitRequest_allowedAfterCooldownWindowExpires() {
        Clock fixedClockAfterCooldown = Clock.fixed(AFTER_COOLDOWN_INSTANT, ZoneOffset.UTC);
        ArtisanFormateurService service = new ArtisanFormateurService(
                formateurRequestRepository,
                artisanRepository,
                userRepository,
                notificationService,
                emailUtil,
                configuredProperties(),
                fixedClockAfterCooldown
        );

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(artisanUser));
        when(artisanRepository.findById("artisan-user-1")).thenReturn(Optional.of(artisanProfile));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisanProfile, FormateurRequestStatus.PENDING))
                .thenReturn(false);

        LocalDateTime rejectionCooldownUntil = LocalDateTime.ofInstant(REJECTION_INSTANT, ZoneOffset.UTC).plusDays(14);
        ArtisanFormateurRequest priorRejectedRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.REJECTED)
                .canReapply(true)
                .cooldownUntil(rejectionCooldownUntil)
                .build();

        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisanProfile))
                .thenReturn(Optional.of(priorRejectedRequest));

        ArtisanFormateurRequest newlySavedRequest = ArtisanFormateurRequest.builder()
                .artisan(artisanProfile)
                .status(FormateurRequestStatus.PENDING)
                .motivation("Reapplying after cooldown expired.")
                .canReapply(true)
                .build();
        newlySavedRequest.setId("new-req-456");

        when(formateurRequestRepository.saveAndFlush(any(ArtisanFormateurRequest.class))).thenReturn(newlySavedRequest);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(adminUser));

        FormateurRequestDTO requestDTO = new FormateurRequestDTO();
        requestDTO.setMotivation("Reapplying after cooldown expired.");

        FormateurRequestResponseDTO response = service.submitRequest(requestDTO);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("new-req-456");
        assertThat(response.getStatus()).isEqualTo(FormateurRequestStatus.PENDING);
        verify(formateurRequestRepository).saveAndFlush(any(ArtisanFormateurRequest.class));
    }
}
