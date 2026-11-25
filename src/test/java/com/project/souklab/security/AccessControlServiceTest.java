package com.project.souklab.security;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.access.ViewerContext;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {
    @Mock UserRepository userRepository;
    @Mock CapabilityService capabilityService;
    private AccessControlService service;

    @BeforeEach
    void setUp() {
        service = new AccessControlService(userRepository, capabilityService, Clock.systemUTC());
    }

    @Test
    void grantsAdminRoleCheckOnlyWhenCapabilityServiceAllowsUserManagement() {
        User admin = User.builder().email("admin@example.com").accountRole(AccountRole.ADMIN).build();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(capabilityService.compute(any(User.class), any()))
                .thenReturn(new ViewerContext(null, AccountRole.ADMIN, PremiumStatus.none(),
                        Set.of(Capability.ADMIN_USERS), false));
        Authentication authentication = new TestingAuthenticationToken("ADMIN@example.com", "credentials");
        authentication.setAuthenticated(true);

        assertThat(service.isAdmin(authentication)).isTrue();
        assertThat(service.canManageUsers(authentication)).isTrue();
    }

    @Test
    void deniesAnonymousUnknownAndNonAdminAccountsEvenIfTokenHasAdminAuthority() {
        assertThat(service.isAdmin(null)).isFalse();
        assertThat(service.isAdmin(new TestingAuthenticationToken("unknown@example.com", "credentials"))).isFalse();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(
                User.builder().email("user@example.com").accountRole(AccountRole.CLIENT).build()));
        when(capabilityService.compute(any(User.class), any()))
                .thenReturn(new ViewerContext(null, AccountRole.CLIENT, PremiumStatus.none(), Set.of(), false));
        Authentication authentication = new TestingAuthenticationToken("user@example.com", "credentials",
                Permission.Admin.USERS);

        assertThat(service.isAdmin(authentication)).isFalse();
        assertThat(service.isArtisan(authentication)).isFalse();
    }

    @Test
    void keepsPermissionPredicatesIndependentFromAdminRoleCapabilities() {
        Authentication feedModerator = new TestingAuthenticationToken("user", "credentials",
                Permission.Admin.FEED);

        assertThat(service.canModerateFeed(feedModerator)).isTrue();
        assertThat(service.canManageFormations(feedModerator)).isFalse();
        assertThat(service.canModerateReports(feedModerator)).isFalse();
    }
}
