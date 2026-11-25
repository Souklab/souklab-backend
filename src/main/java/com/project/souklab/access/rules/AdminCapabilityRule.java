package com.project.souklab.access.rules;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityRule;
import com.project.souklab.access.CapabilityRuleContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AdminCapabilityRule implements CapabilityRule {
    @Override
    public void apply(CapabilityRuleContext context, Set<Capability> capabilities) {
        if (context.role() == AccountRole.ADMIN) {
            addIfGranted(context, capabilities, Permission.Admin.USERS, Capability.ADMIN_USERS);
            addIfGranted(context, capabilities, Permission.Admin.FORMATIONS, Capability.ADMIN_FORMATIONS);
            addIfGranted(context, capabilities, Permission.Admin.FEED, Capability.ADMIN_FEED);
            addIfGranted(context, capabilities, Permission.Admin.REPORTS, Capability.ADMIN_REPORTS);
            addIfGranted(context, capabilities, Permission.Admin.CATALOG, Capability.ADMIN_CATALOG);
            addIfGranted(context, capabilities, Permission.Analytics.ADMIN, Capability.ADMIN_AUDIT);
            addIfGranted(context, capabilities, Permission.Financial.ADMIN, Capability.ADMIN_FINANCE);
        }
    }

    private void addIfGranted(CapabilityRuleContext context, Set<Capability> capabilities,
                             Permission permission, Capability capability) {
        boolean granted = context.user().getPermissions() != null && context.user().getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> permission.matches(value.getPermissionKey()));
        if (granted) capabilities.add(capability);
    }
}
