package com.project.souklab.access.rules;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityRule;
import com.project.souklab.access.CapabilityRuleContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FormationCapabilityRule implements CapabilityRule {
    @Override
    public void apply(CapabilityRuleContext context, Set<Capability> capabilities) {
        if (context.role() == AccountRole.ADMIN && hasPermission(context, Permission.Admin.FORMATIONS)) {
            capabilities.add(Capability.VIEW_FORMATIONS);
            capabilities.add(Capability.ADMIN_FORMATIONS);
        }
        if (context.role() == AccountRole.ARTISAN && hasPermission(context, Permission.Artisan.FORMATIONS)) {
            capabilities.add(Capability.VIEW_FORMATIONS);
            capabilities.add(Capability.ENROLL_FORMATIONS);
            if (context.formateur()) {
                capabilities.add(Capability.PUBLISH_FORMATIONS);
            }
        }
        if (context.role() == AccountRole.ARTISAN && !context.formateur()
                && hasPermission(context, Permission.Artisan.FORMATIONS)) {
            capabilities.add(Capability.REQUEST_FORMATEUR);
        }
    }

    private boolean hasPermission(CapabilityRuleContext context, Permission permission) {
        return context.user().getPermissions() != null && context.user().getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> permission.matches(value.getPermissionKey()));
    }
}
