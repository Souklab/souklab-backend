package com.project.souklab.security;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.ViewerContext;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.User;
import com.project.souklab.model.AccountRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** Centralized authorization predicates for Spring method security and domain services. */
@Component("accessControl")
@RequiredArgsConstructor
public class AccessControlService {

    private final UserRepository userRepository;
    private final CapabilityService capabilityService;
    private final Clock clock;

    public boolean hasPermission(Authentication authentication, Permission permission) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(permission::matches);
    }

    public boolean isAdmin(Authentication authentication) {
        ViewerContext context = context(authentication);
        return context != null && context.can(Capability.ADMIN_USERS);
    }

    public boolean canManageUsers(Authentication authentication) {
        return isAdmin(authentication);
    }

    public boolean canManageFormations(Authentication authentication) {
        return hasPermission(authentication, Permission.Admin.FORMATIONS);
    }

    public boolean canModerateFeed(Authentication authentication) {
        return hasPermission(authentication, Permission.Admin.FEED);
    }

    public boolean canModerateReports(Authentication authentication) {
        return hasPermission(authentication, Permission.Admin.REPORTS);
    }

    public boolean canManageFinancialOperations(Authentication authentication) {
        return hasPermission(authentication, Permission.Financial.ADMIN);
    }

    public boolean canViewAnalytics(Authentication authentication) {
        return hasPermission(authentication, Permission.Analytics.ADMIN);
    }

    public boolean canManageArtisanFormations(Authentication authentication) {
        return hasPermission(authentication, Permission.Artisan.FORMATIONS);
    }

    public boolean canManageArtisanContent(Authentication authentication) {
        return hasPermission(authentication, Permission.Artisan.CONTENT);
    }

    public boolean canManageArtisanReviews(Authentication authentication) {
        return hasPermission(authentication, Permission.Artisan.REVIEWS);
    }

    public boolean isArtisan(Authentication authentication) {
        ViewerContext context = context(authentication);
        return context != null && context.role() == AccountRole.ARTISAN;
    }

    public boolean canReadProfile(Authentication authentication) {
        return hasPermission(authentication, Permission.Profile.READ);
    }

    public boolean canWriteProfile(Authentication authentication) {
        return hasPermission(authentication, Permission.Profile.WRITE);
    }

    public boolean canManageCatalog(Authentication authentication) {
        return hasPermission(authentication, Permission.Admin.CATALOG);
    }

    /** Determines whether the authenticated principal may manage client favorites. */
    public boolean canManageFavorites(Authentication authentication) {
        return hasPermission(authentication, Permission.Client.FAVORITES);
    }

    private ViewerContext context(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        User user = userRepository.findByEmail(authentication.getName().toLowerCase(Locale.ROOT)).orElse(null);
        return user == null ? null : capabilityService.compute(user, Instant.now(clock));
    }
}
