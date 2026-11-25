package com.project.souklab.access;

import com.project.souklab.model.AccountRole;
import com.project.souklab.model.User;

public record CapabilityRuleContext(User user, AccountRole role, PremiumStatus premiumStatus, boolean formateur) {
}
