package com.project.souklab.access;

import com.project.souklab.access.rules.AdminCapabilityRule;
import com.project.souklab.access.rules.FormationCapabilityRule;
import com.project.souklab.access.rules.IdentityCapabilityRule;
import com.project.souklab.access.rules.MessagingCapabilityRule;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuleTest {
    @Test
    void messagingRuleRequiresAnEligibleRoleAndEnabledPermission() {
        MessagingCapabilityRule rule = new MessagingCapabilityRule();
        User user = user(AccountRole.CLIENT, Permission.Message.SEND);
        CapabilityRuleContext premiumClient = context(user, AccountRole.CLIENT, true, false);
        Set<Capability> actual = EnumSet.noneOf(Capability.class);

        rule.apply(premiumClient, actual);

        assertThat(actual).containsExactlyInAnyOrder(Capability.START_CONVERSATION, Capability.SEND_MESSAGE);
        actual.clear();
        rule.apply(context(user, AccountRole.CLIENT, false, false), actual);
        assertThat(actual).isEmpty();
        user.setPermissions(Set.of(disabled(Permission.Message.SEND)));
        rule.apply(premiumClient, actual);
        assertThat(actual).isEmpty();
    }

    @Test
    void identityRuleAllowsPremiumMembersAndAdminsWithUserManagementPermission() {
        IdentityCapabilityRule rule = new IdentityCapabilityRule();
        Set<Capability> actual = EnumSet.noneOf(Capability.class);
        rule.apply(context(user(AccountRole.ARTISAN), AccountRole.ARTISAN, true, false), actual);
        assertThat(actual).containsExactly(Capability.VIEW_ARTISAN_IDENTITY);
        actual.clear();
        rule.apply(context(user(AccountRole.ADMIN, Permission.Admin.USERS), AccountRole.ADMIN, false, false), actual);
        assertThat(actual).containsExactly(Capability.VIEW_ARTISAN_IDENTITY);
        actual.clear();
        rule.apply(context(user(AccountRole.ADMIN), AccountRole.ADMIN, false, false), actual);
        assertThat(actual).isEmpty();
    }

    @Test
    void formationRuleAppliesRoleAndFormateurMatrix() {
        FormationCapabilityRule rule = new FormationCapabilityRule();
        Set<Capability> actual = EnumSet.noneOf(Capability.class);
        rule.apply(context(user(AccountRole.ARTISAN, Permission.Artisan.FORMATIONS), AccountRole.ARTISAN, false, false), actual);
        assertThat(actual).containsExactlyInAnyOrder(Capability.VIEW_FORMATIONS, Capability.ENROLL_FORMATIONS);
        actual.clear();
        rule.apply(context(user(AccountRole.ARTISAN, Permission.Artisan.FORMATIONS), AccountRole.ARTISAN, false, true), actual);
        assertThat(actual).containsExactlyInAnyOrder(Capability.VIEW_FORMATIONS, Capability.ENROLL_FORMATIONS,
                Capability.PUBLISH_FORMATIONS);
        actual.clear();
        rule.apply(context(user(AccountRole.ADMIN, Permission.Admin.FORMATIONS), AccountRole.ADMIN, false, false), actual);
        assertThat(actual).containsExactlyInAnyOrder(Capability.VIEW_FORMATIONS, Capability.MANAGE_FORMATIONS,
                Capability.MANAGE_FORMATEURS);
    }

    @Test
    void adminRuleRequiresAdminRoleAndEnabledUserManagementPermission() {
        AdminCapabilityRule rule = new AdminCapabilityRule();
        Set<Capability> actual = EnumSet.noneOf(Capability.class);
        rule.apply(context(user(AccountRole.ADMIN, Permission.Admin.USERS), AccountRole.ADMIN, false, false), actual);
        assertThat(actual).containsExactly(Capability.MANAGE_USERS);
        actual.clear();
        rule.apply(context(user(AccountRole.CLIENT, Permission.Admin.USERS), AccountRole.CLIENT, false, false), actual);
        assertThat(actual).isEmpty();
    }

    private static CapabilityRuleContext context(User user, AccountRole role, boolean premium, boolean formateur) {
        return new CapabilityRuleContext(user, role,
                new PremiumStatus(premium, null, false, null, false), formateur);
    }

    private static User user(AccountRole role, Permission... permissions) {
        User user = new User();
        user.setAccountRole(role);
        user.setPermissions(Arrays.stream(permissions).map(CapabilityRuleTest::enabled).collect(Collectors.toSet()));
        return user;
    }

    private static AuthorizationPermission enabled(Permission permission) {
        AuthorizationPermission value = new AuthorizationPermission();
        value.setPermissionKey(permission.value());
        value.setEnabled(true);
        return value;
    }

    private static AuthorizationPermission disabled(Permission permission) {
        AuthorizationPermission value = enabled(permission);
        value.setEnabled(false);
        return value;
    }
}
