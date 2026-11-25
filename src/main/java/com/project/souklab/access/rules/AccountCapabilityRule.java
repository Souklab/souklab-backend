package com.project.souklab.access.rules;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityRule;
import com.project.souklab.access.CapabilityRuleContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Computes account-level capabilities that do not depend on a target resource. */
@Component
public class AccountCapabilityRule implements CapabilityRule {
    @Override
    public void apply(CapabilityRuleContext context, Set<Capability> capabilities) {
        AccountRole role = context.role();
        if (role == AccountRole.CLIENT || role == AccountRole.ARTISAN) {
            capabilities.add(Capability.HAS_MEMBER_PROFILE);
            capabilities.add(Capability.DELETE_ACCOUNT);
            capabilities.add(Capability.MANAGE_SUBSCRIPTION);
        }
        if (role == AccountRole.CLIENT && hasPermission(context, Permission.Client.FAVORITES)) {
            capabilities.add(Capability.MANAGE_FAVORITES);
        }
        if (role == AccountRole.ARTISAN) {
            if (hasPermission(context, Permission.Artisan.CONTENT)) {
                capabilities.add(Capability.PUBLISH_POSTS);
                capabilities.add(Capability.MANAGE_GALLERY);
                capabilities.add(Capability.MANAGE_CERTIFICATIONS);
            }
            if (context.user().getArtisan() != null && context.user().getArtisan().isVerified()) {
                capabilities.add(Capability.HAS_PUBLIC_PROFILE);
            }
        }
    }

    private boolean hasPermission(CapabilityRuleContext context, Permission permission) {
        return context.user().getPermissions() != null && context.user().getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> permission.matches(value.getPermissionKey()));
    }
}
