package com.project.souklab.access.rules;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityRule;
import com.project.souklab.access.CapabilityRuleContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class MessagingCapabilityRule implements CapabilityRule {
    @Override
    public void apply(CapabilityRuleContext context, Set<Capability> capabilities) {
        boolean eligibleRole = context.role() == AccountRole.ADMIN || context.role() == AccountRole.ARTISAN
                || (context.role() == AccountRole.CLIENT && context.premiumStatus().active());
        if (eligibleRole && hasPermission(context, Permission.Message.SEND)) {
            capabilities.add(Capability.START_CONVERSATION);
            capabilities.add(Capability.SEND_MESSAGE);
        }
    }

    private boolean hasPermission(CapabilityRuleContext context, Permission permission) {
        return context.user().getPermissions() != null && context.user().getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> permission.matches(value.getPermissionKey()));
    }
}
