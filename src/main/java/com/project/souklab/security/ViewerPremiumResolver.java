package com.project.souklab.security;

import com.project.souklab.dao.UserRepository;
import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityService;
import com.project.souklab.model.User;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.time.Clock;
import java.time.Instant;

/**
 * Shared component that determines whether contact information should be locked
 * for the current request's viewer.
 *
 * <p>Locking rules:
 * <ul>
 *   <li>Admins — never locked.</li>
 *   <li>Profile owner (isSelf) — never locked.</li>
 *   <li>Premium clients or premium artisans — unlocked.</li>
 *   <li>Non-premium authenticated users — locked.</li>
 *   <li>Anonymous callers — locked (directory endpoint blocks them upstream via
 *       {@code @PreAuthorize("isAuthenticated()")}; this covers any edge case).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ViewerPremiumResolver {

    private final UserRepository userRepository;
    private final CapabilityService capabilityService;
    private final Clock clock;

    /**
     * Resolves contact-info lock state for the viewer inferred from the current
     * {@link SecurityContext}.
     *
     * <p>Intended for directory searches before individual result rows are mapped.
     * A row owned by the viewer is exempted separately during mapping.
     *
     * @return {@code true} if contact fields must be masked; {@code false} if the
     *         viewer has premium access
     */
    @Transactional(readOnly = true)
    public boolean isContactInfoLocked() {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            return true;
        }

        User viewer = userRepository.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElse(null);
        if (viewer == null) {
            return true;
        }

        return isContactInfoLocked(viewer, false, false);
    }

    /** Returns whether the current authenticated account owns the supplied profile. */
    public boolean isSelf(User subject) {
        if (subject == null || subject.getEmail() == null) {
            return false;
        }
        String email = SecurityUtils.getCurrentUsername();
        return email != null && email.equalsIgnoreCase(subject.getEmail());
    }

    /**
     * Resolves whether contact/name information for a specific user must be
     * masked for the current viewer. This is used by public responses that
     * expose an artisan as an author, such as feed posts and comments.
     *
     * @param subject the user whose artisan identity is being exposed
     * @return {@code true} when the subject's artisan identity must be masked
     */
    @Transactional(readOnly = true)
    public boolean isContactInfoLockedFor(User subject) {
        if (subject == null) {
            return true;
        }

        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            return true;
        }

        User viewer = userRepository.findByEmail(email.toLowerCase(Locale.ROOT)).orElse(null);
        if (viewer == null) {
            return true;
        }

        boolean isSelf = viewer.getId() != null && viewer.getId().equals(subject.getId());
        return isContactInfoLocked(viewer, isSelf, false);
    }

    /**
     * Resolves contact-info lock state for an already-loaded viewer, with explicit
     * self-view and admin flags.
     *
     * <p>Intended for the artisan profile path, where the viewer entity and context
     * flags are computed before calling this method.
     *
     * @param viewer  the authenticated {@link User} performing the request
     * @param isSelf  {@code true} if the viewer is viewing their own profile
     * @param isAdmin {@code true} if the viewer holds administrator permissions
     * @return {@code true} if contact fields must be masked; {@code false} otherwise
     */
    public boolean isContactInfoLocked(User viewer, boolean isSelf, boolean isAdmin) {
        if (isSelf || isAdmin) {
            return false;
        }
        return !capabilityService.compute(viewer, Instant.now(clock)).can(Capability.VIEW_ARTISAN_IDENTITY);
    }

    /**
     * Standardizes the anonymised display name for an artisan whose contact or identity is masked.
     * Produces {@code "Artisan #XXXXX"} (the last 5 characters of the artisan ID, uppercased).
     *
     * @param artisanId unique identifier of the artisan
     * @return anonymised artisan display label
     */
    public static String maskArtisanName(String artisanId) {
        if (artisanId == null || artisanId.isBlank()) {
            return "Artisan #?????";
        }
        String suffix = artisanId.length() >= 5
                ? artisanId.substring(artisanId.length() - 5)
                : artisanId;
        return "Artisan #" + suffix.toUpperCase(Locale.ROOT);
    }
}
