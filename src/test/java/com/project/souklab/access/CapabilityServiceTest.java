package com.project.souklab.access;

import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.model.*;
import com.project.souklab.access.rules.AdminCapabilityRule;
import com.project.souklab.access.rules.FormationCapabilityRule;
import com.project.souklab.access.rules.IdentityCapabilityRule;
import com.project.souklab.access.rules.MessagingCapabilityRule;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");
    @Mock ClientSubscriptionRepository clients;
    @Mock ArtisanSubscriptionRepository artisans;
    private CapabilityService service;

    @BeforeEach
    void setUp() {
        service = new CapabilityService(clients, artisans, List.of(
                new MessagingCapabilityRule(), new IdentityCapabilityRule(),
                new FormationCapabilityRule(), new AdminCapabilityRule()));
    }


    @Test
    void visitorHasNoRolePremiumOrCapabilities() {
        ViewerContext result = service.visitor();
        assertThat(result.role()).isNull();
        assertThat(result.premiumStatus().active()).isFalse();
        assertThat(result.capabilities()).isEmpty();
    }

    @Test
    void administratorsUseExplicitRoleAndEnabledPermissions() {
        User admin = user(AccountRole.ADMIN, true);
        admin.setPermissions(new HashSet<>(Set.of(permission(Permission.Message.SEND),
                permission(Permission.Admin.USERS), permission(Permission.Admin.FORMATIONS))));

        ViewerContext result = service.compute(admin, NOW);

        assertThat(result.role()).isEqualTo(AccountRole.ADMIN);
        assertThat(result.can(Capability.START_CONVERSATION)).isTrue();
        assertThat(result.can(Capability.VIEW_ARTISAN_IDENTITY)).isTrue();
        assertThat(result.can(Capability.VIEW_FORMATIONS)).isTrue();
        assertThat(result.can(Capability.MANAGE_USERS)).isTrue();

        admin.setPermissions(Set.of(permission(Permission.Message.SEND)));
        result = service.compute(admin, NOW);
        assertThat(result.can(Capability.MANAGE_USERS)).isFalse();
        assertThat(result.can(Capability.VIEW_ARTISAN_IDENTITY)).isFalse();
        assertThat(result.can(Capability.MANAGE_FORMATIONS)).isFalse();
    }

    @Test
    void freeClientCannotMessageOrViewIdentity() {
        ViewerContext result = service.compute(user(AccountRole.CLIENT, false), NOW);
        assertThat(result.capabilities()).isEmpty();
        assertThat(result.premiumStatus().active()).isFalse();
    }

    @Test
    void activeAndGraceClientKeepMessagingAndIdentityCapabilities() {
        User client = user(AccountRole.CLIENT, false);
        when(clients.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(client.getId(), List.of(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(clientSubscription(LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC))));

        ViewerContext result = service.compute(client, NOW);
        assertThat(result.can(Capability.START_CONVERSATION)).isTrue();
        assertThat(result.can(Capability.VIEW_ARTISAN_IDENTITY)).isTrue();
        assertThat(result.premiumStatus().inGrace()).isTrue();
        assertThat(result.premiumStatus().graceEndsAt()).isEqualTo(NOW.plus(CapabilityService.PREMIUM_GRACE).minusSeconds(60));
    }

    @Test
    void clientLosesAccessImmediatelyAfterExpiryGrace() {
        User client = user(AccountRole.CLIENT, false);
        LocalDateTime expiry = LocalDateTime.ofInstant(NOW.minus(CapabilityService.PREMIUM_GRACE).minusSeconds(1), ZoneOffset.UTC);
        when(clients.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(client.getId(), List.of(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(clientSubscription(expiry)));

        ViewerContext result = service.compute(client, NOW);
        assertThat(result.can(Capability.SEND_MESSAGE)).isFalse();
        assertThat(result.can(Capability.VIEW_ARTISAN_IDENTITY)).isFalse();
        assertThat(result.premiumStatus().active()).isFalse();
    }

    @Test
    void artisansCanMessageAndEnrollWithoutPremiumButOnlyFormateursCanPublish() {
        User artisan = user(AccountRole.ARTISAN, true);
        artisan.setArtisan(Artisan.builder().user(artisan).isTeacher(false).build());
        ViewerContext result = service.compute(artisan, NOW);
        assertThat(result.can(Capability.SEND_MESSAGE)).isTrue();
        assertThat(result.can(Capability.ENROLL_FORMATIONS)).isTrue();
        assertThat(result.can(Capability.PUBLISH_FORMATIONS)).isFalse();

        artisan.getArtisan().setTeacher(true);
        assertThat(service.compute(artisan, NOW).can(Capability.PUBLISH_FORMATIONS)).isTrue();
    }

    @Test
    void premiumArtisanCanViewIdentityAndAdminRequiresFormationPermission() {
        User artisan = user(AccountRole.ARTISAN, true);
        when(artisans.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(artisan.getId(), List.of(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(artisanSubscription(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC))));
        assertThat(service.compute(artisan, NOW).can(Capability.VIEW_ARTISAN_IDENTITY)).isTrue();

        User admin = user(AccountRole.ADMIN, true);
        assertThat(service.compute(admin, NOW).can(Capability.MANAGE_FORMATIONS)).isFalse();
        admin.setPermissions(new HashSet<>(Set.of(permission(Permission.Admin.FORMATIONS))));
        assertThat(service.compute(admin, NOW).can(Capability.MANAGE_FORMATIONS)).isTrue();
    }

    @Test
    void computeAllLoadsSubscriptionsInBatchesAndUsesTheLatestActiveEntitlement() {
        User client = user(AccountRole.CLIENT, false);
        User artisan = user(AccountRole.ARTISAN, true);
        artisan.setArtisan(Artisan.builder().user(artisan).isTeacher(true).build());
        ClientSubscription activeClientSub = clientSubscription(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        activeClientSub.setAccount(client);
        ArtisanSubscription activeArtisanSub = artisanSubscription(LocalDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));
        activeArtisanSub.setAccount(artisan);
        when(clients.findByAccountIdInAndStatusInOrderByCreatedAtDesc(
                List.of(client.getId()), List.of(SubscriptionStatus.ACTIVE))).thenReturn(List.of(activeClientSub));
        when(artisans.findByAccountIdInAndStatusInOrderByCreatedAtDesc(
                List.of(artisan.getId()), List.of(SubscriptionStatus.ACTIVE))).thenReturn(List.of(activeArtisanSub));

        var contexts = service.computeAll(List.of(client, artisan), NOW);

        assertThat(contexts.get(client.getId()).can(Capability.START_CONVERSATION)).isTrue();
        assertThat(contexts.get(artisan.getId()).can(Capability.PUBLISH_FORMATIONS)).isTrue();
        assertThat(contexts.get(artisan.getId()).premiumStatus().active()).isTrue();
        verify(clients).findByAccountIdInAndStatusInOrderByCreatedAtDesc(
                List.of(client.getId()), List.of(SubscriptionStatus.ACTIVE));
        verify(artisans).findByAccountIdInAndStatusInOrderByCreatedAtDesc(
                List.of(artisan.getId()), List.of(SubscriptionStatus.ACTIVE));
    }

    private User user(AccountRole role, boolean messagingPermission) {
        User user = new User();
        user.setId("user-" + role);
        user.setAccountRole(role);
        Set<AuthorizationPermission> permissions = new HashSet<>();
        permissions.add(permission(Permission.Message.SEND));
        if (role == AccountRole.ARTISAN && messagingPermission) {
            permissions.add(permission(Permission.Artisan.FORMATIONS));
        }
        user.setPermissions(permissions);
        return user;
    }

    private AuthorizationPermission permission(Permission permission) {
        AuthorizationPermission value = new AuthorizationPermission();
        value.setPermissionKey(permission.value());
        value.setEnabled(true);
        return value;
    }

    private ClientSubscription clientSubscription(LocalDateTime expiresAt) {
        ClientSubscription value = new ClientSubscription();
        value.setStatus(SubscriptionStatus.ACTIVE);
        value.setExpiresAt(expiresAt);
        return value;
    }

    private ArtisanSubscription artisanSubscription(LocalDateTime expiresAt) {
        ArtisanSubscription value = new ArtisanSubscription();
        value.setStatus(SubscriptionStatus.ACTIVE);
        value.setExpiresAt(expiresAt);
        return value;
    }
}
