package com.project.souklab.access;

import com.project.souklab.model.AccountRole;
import java.util.Set;

public record ViewerContext(String userId, AccountRole role, PremiumStatus premiumStatus,
                            Set<Capability> capabilities, boolean formateur) {
    public ViewerContext {
        capabilities = Set.copyOf(capabilities);
    }

    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }
}
