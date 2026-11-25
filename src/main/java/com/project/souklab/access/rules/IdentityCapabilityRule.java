package com.project.souklab.access.rules;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityRule;
import com.project.souklab.access.CapabilityRuleContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class IdentityCapabilityRule implements CapabilityRule {
    @Override
    public void apply(CapabilityRuleContext context, Set<Capability> capabilities) {
        boolean adminAllowed = context.role() == AccountRole.ADMIN && hasPermission(context, Permission.Admin.USERS);
        boolean premiumMember = (context.role() == AccountRole.CLIENT || context.role() == AccountRole.ARTISAN)
                && context.premiumStatus().active();
        if (adminAllowed || premiumMember) {
            capabilities.add(Capability.VIEW_ARTISAN_IDENTITY);
        }
    }

    private boolean hasPermission(CapabilityRuleContext context, Permission permission) {
        return context.user().getPermissions() != null && context.user().getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> permission.matches(value.getPermissionKey()));
    }
}
