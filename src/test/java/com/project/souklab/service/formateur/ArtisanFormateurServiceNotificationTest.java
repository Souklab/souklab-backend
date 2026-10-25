package com.project.souklab.service.formateur;
import com.project.souklab.security.Permission;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.formateur.FormateurRequestDTO;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtisanFormateurServiceNotificationTest {

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

    @Spy
    private Clock clock = Clock.systemUTC();

    @Spy
    private ArtisanProperties artisanProperties = new ArtisanProperties();

    @InjectMocks
    private ArtisanFormateurService artisanFormateurService;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("artisan@example.com");
        SecurityContextHolder.setContext(securityContext);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Dual-dispatch notification and email include artisan's full name when present")
    void testSubmitRequest_whenArtisanNamePresent_includesNameInNotificationAndEmail() {
        User user = User.builder()
                .email("artisan@example.com")
                .firstName("John")
                .lastName("Doe")
                .status(AccountStatus.ACTIVE)
                .build();
        user.setId("user-1");

        Artisan artisan = Artisan.builder()
                .id("user-1")
                .user(user)
                .isTeacher(false)
                .build();

        User admin = User.builder()
                .email("admin@example.com")
                .status(AccountStatus.ACTIVE)
                .build();
        admin.setId("admin-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("user-1")).thenReturn(Optional.of(artisan));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisan, FormateurRequestStatus.PENDING)).thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisan)).thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisan)
                .status(FormateurRequestStatus.PENDING)
                .motivation("I want to teach pottery")
                .canReapply(true)
                .build();
        saved.setId("req-1");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(admin));

        FormateurRequestDTO dto = FormateurRequestDTO.builder()
                .motivation("I want to teach pottery")
                .build();

        artisanFormateurService.submitRequest(dto);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(admin), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("req-1"));
        assertThat(notifCaptor.getValue())
                .isEqualTo("New artisan formateur request submitted by John Doe (artisan@example.com): \"I want to teach pottery\"");

        verify(emailUtil).sendFormateurRequestSubmittedNoticeToAdmin(
                eq("admin@example.com"),
                eq("artisan@example.com"),
                eq("John Doe"),
                eq("I want to teach pottery")
        );
    }

    @Test
    @DisplayName("Dual-dispatch falls back to email-only when firstName and lastName are missing")
    void testSubmitRequest_whenArtisanNameMissing_fallsBackToEmailOnly() {
        User user = User.builder()
                .email("artisan@example.com")
                .firstName(null)
                .lastName("")
                .status(AccountStatus.ACTIVE)
                .build();
        user.setId("user-2");

        Artisan artisan = Artisan.builder()
                .id("user-2")
                .user(user)
                .isTeacher(false)
                .build();

        User admin = User.builder()
                .email("admin@example.com")
                .status(AccountStatus.ACTIVE)
                .build();
        admin.setId("admin-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("user-2")).thenReturn(Optional.of(artisan));
        when(formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(artisan, FormateurRequestStatus.PENDING)).thenReturn(false);
        when(formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisan)).thenReturn(Optional.empty());

        ArtisanFormateurRequest saved = ArtisanFormateurRequest.builder()
                .artisan(artisan)
                .status(FormateurRequestStatus.PENDING)
                .motivation(null)
                .canReapply(true)
                .build();
        saved.setId("req-2");
        when(formateurRequestRepository.saveAndFlush(any())).thenReturn(saved);
        when(userRepository.findByPermissionKey(Permission.Admin.USERS.value())).thenReturn(List.of(admin));

        artisanFormateurService.submitRequest(null);

        ArgumentCaptor<String> notifCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(admin), notifCaptor.capture(), eq(NotificationType.Formateur.Request.SUBMITTED), eq("req-2"));
        assertThat(notifCaptor.getValue())
                .isEqualTo("New artisan formateur request submitted by artisan@example.com");

        verify(emailUtil).sendFormateurRequestSubmittedNoticeToAdmin(
                eq("admin@example.com"),
                eq("artisan@example.com"),
                eq(""),
                isNull()
        );
    }
}
