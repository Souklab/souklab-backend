package com.project.souklab.security;

import com.project.souklab.dao.UserRepository;
import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.access.ViewerContext;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.Client;
import com.project.souklab.model.User;
import com.project.souklab.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link ViewerPremiumResolver} contact-info locking logic
 * across all viewer types: anonymous, non-premium client, premium client,
 * non-premium artisan, premium artisan, self-view, and admin.
 */
@ExtendWith(MockitoExtension.class)
class ViewerPremiumResolverTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CapabilityService capabilityService;

    private ViewerPremiumResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(capabilityService.compute(any(), any()))
                .thenAnswer(invocation -> {
                    User viewer = invocation.getArgument(0);
                    boolean granted = (viewer.getClient() != null && viewer.getClient().isPremium())
                            || (viewer.getArtisan() != null && viewer.getArtisan().isPremium())
                            || viewer.getPermissions().stream().anyMatch(p -> Permission.Admin.USERS.matches(p.getPermissionKey()));
                    return new ViewerContext(viewer.getId(), viewer.getAccountRole(),
                            PremiumStatus.none(), granted ? Set.of(Capability.VIEW_ARTISAN_IDENTITY) : Set.of(), false);
                });
        resolver = new ViewerPremiumResolver(userRepository, capabilityService,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    // ── Overloaded method tests (pure logic, no DB) ─────────────────────────

    @Test
    @DisplayName("isContactInfoLocked: self-view is never locked regardless of premium status")
    void isContactInfoLocked_selfView_returnsFalse() {
        User nonPremiumUser = User.builder().build();
        assertThat(resolver.isContactInfoLocked(nonPremiumUser, true, false)).isFalse();
    }

    @Test
    @DisplayName("isContactInfoLocked: admin viewer is never locked")
    void isContactInfoLocked_adminViewer_returnsFalse() {
        User adminUser = User.builder().build();
        assertThat(resolver.isContactInfoLocked(adminUser, false, true)).isFalse();
    }

    @Test
    @DisplayName("isContactInfoLocked: premium client is not locked")
    void isContactInfoLocked_premiumClient_returnsFalse() {
        Client premiumClient = Client.builder().isPremium(true).build();
        User user = User.builder().client(premiumClient).build();
        assertThat(resolver.isContactInfoLocked(user, false, false)).isFalse();
    }

    @Test
    @DisplayName("isContactInfoLocked: non-premium client is locked")
    void isContactInfoLocked_nonPremiumClient_returnsTrue() {
        Client nonPremiumClient = Client.builder().isPremium(false).build();
        User user = User.builder().client(nonPremiumClient).build();
        assertThat(resolver.isContactInfoLocked(user, false, false)).isTrue();
    }

    @Test
    @DisplayName("isContactInfoLocked: premium artisan viewer is not locked")
    void isContactInfoLocked_premiumArtisan_returnsFalse() {
        Artisan premiumArtisan = Artisan.builder().isPremium(true).build();
        User user = User.builder().artisan(premiumArtisan).build();
        assertThat(resolver.isContactInfoLocked(user, false, false)).isFalse();
    }

    @Test
    @DisplayName("isContactInfoLocked: non-premium artisan viewer is locked")
    void isContactInfoLocked_nonPremiumArtisan_returnsTrue() {
        Artisan nonPremiumArtisan = Artisan.builder().isPremium(false).build();
        User user = User.builder().artisan(nonPremiumArtisan).build();
        assertThat(resolver.isContactInfoLocked(user, false, false)).isTrue();
    }

    @Test
    @DisplayName("isContactInfoLocked: user with no client or artisan profile is locked")
    void isContactInfoLocked_noProfile_returnsTrue() {
        User userWithNoProfile = User.builder().build();
        assertThat(resolver.isContactInfoLocked(userWithNoProfile, false, false)).isTrue();
    }

    // ── No-arg method tests (reads SecurityContext + DB) ────────────────────

    @Test
    @DisplayName("isContactInfoLocked (no-arg): anonymous caller (null email) is locked")
    void isContactInfoLocked_anonymousCaller_returnsTrue() {
        try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
            securityMock.when(SecurityUtils::getCurrentUsername).thenReturn(null);

            assertThat(resolver.isContactInfoLocked()).isTrue();
        }
    }

    @Test
    @DisplayName("isContactInfoLocked (no-arg): unknown email (no DB record) is locked")
    void isContactInfoLocked_unknownEmail_returnsTrue() {
        try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
            securityMock.when(SecurityUtils::getCurrentUsername).thenReturn("ghost@souklab.dz");
            when(userRepository.findByEmail("ghost@souklab.dz")).thenReturn(Optional.empty());

            assertThat(resolver.isContactInfoLocked()).isTrue();
        }
    }

    @Test
    @DisplayName("isContactInfoLocked (no-arg): premium client viewer is unlocked")
    void isContactInfoLocked_premiumClientViewer_returnsFalse() {
        Client premiumClient = Client.builder().isPremium(true).build();
        User user = User.builder()
                .email("premium@souklab.dz")
                .client(premiumClient)
                .build();

        try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
            securityMock.when(SecurityUtils::getCurrentUsername).thenReturn("premium@souklab.dz");
            when(userRepository.findByEmail("premium@souklab.dz")).thenReturn(Optional.of(user));

            assertThat(resolver.isContactInfoLocked()).isFalse();
        }
    }

    @Test
    @DisplayName("isContactInfoLocked (no-arg): admin viewer is unlocked")
    void isContactInfoLocked_adminViewer_fromContext_returnsFalse() {
        // AuthorizationPermission uses @AllArgsConstructor: (permissionKey, description, enabled)
        AuthorizationPermission adminPermission = new AuthorizationPermission(
                Permission.Admin.USERS.value(), "Manage users", true);
        User adminUser = User.builder()
                .email("admin@souklab.dz")
                .permissions(Set.of(adminPermission))
                .build();

        try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
            securityMock.when(SecurityUtils::getCurrentUsername).thenReturn("admin@souklab.dz");
            when(userRepository.findByEmail("admin@souklab.dz")).thenReturn(Optional.of(adminUser));

            assertThat(resolver.isContactInfoLocked()).isFalse();
        }
    }

    @Test
    @DisplayName("isContactInfoLocked (no-arg): non-premium authenticated client is locked")
    void isContactInfoLocked_nonPremiumAuthenticatedClient_returnsTrue() {
        Client nonPremiumClient = Client.builder().isPremium(false).build();
        User user = User.builder()
                .email("basic@souklab.dz")
                .client(nonPremiumClient)
                .build();

        try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
            securityMock.when(SecurityUtils::getCurrentUsername).thenReturn("basic@souklab.dz");
            when(userRepository.findByEmail("basic@souklab.dz")).thenReturn(Optional.of(user));

            assertThat(resolver.isContactInfoLocked()).isTrue();
        }
    }
}
