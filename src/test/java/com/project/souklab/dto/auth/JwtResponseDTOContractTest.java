package com.project.souklab.dto.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtResponseDTOContractTest {
    @Test
    void exposesLiveRoleCapabilitiesAndPremiumSnapshotAlongsideTokens() throws Exception {
        JwtResponseDTO response = JwtResponseDTO.builder()
                .accountRole(AccountRole.CLIENT)
                .capabilities(Set.of(Capability.SEND_MESSAGE, Capability.VIEW_ARTISAN_IDENTITY))
                .premium(new PremiumStatus(true, Instant.parse("2026-12-01T00:00:00Z"), false,
                        Instant.parse("2026-12-04T00:00:00Z"), false))
                .build();

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(json.path("accountRole").asText()).isEqualTo("CLIENT");
        assertThat(json.path("capabilities").toString()).contains("SEND_MESSAGE", "VIEW_ARTISAN_IDENTITY");
        assertThat(json.path("premium").path("active").asBoolean()).isTrue();
        assertThat(json.path("premium").path("inGrace").asBoolean()).isFalse();
        assertThat(json.path("premium").path("cancelAtPeriodEnd").asBoolean()).isFalse();
    }

    @Test
    void capabilityEnumPublishesTheFrontendContract() {
        assertThat(Capability.values()).contains(Capability.REQUEST_FORMATEUR, Capability.PUBLISH_POSTS,
                Capability.MANAGE_GALLERY, Capability.MANAGE_CERTIFICATIONS, Capability.MANAGE_FAVORITES,
                Capability.MANAGE_SUBSCRIPTION, Capability.HAS_MEMBER_PROFILE, Capability.HAS_PUBLIC_PROFILE,
                Capability.DELETE_ACCOUNT, Capability.ADMIN_USERS, Capability.ADMIN_AUDIT,
                Capability.ADMIN_FORMATIONS, Capability.ADMIN_FEED, Capability.ADMIN_REPORTS,
                Capability.ADMIN_CATALOG, Capability.ADMIN_FINANCE);
    }

    @Test
    void currentUserProfileUsesThePremiumContractName() throws Exception {
        ClientProfileResponseDTO profile = ClientProfileResponseDTO.builder()
                .accountRole(AccountRole.CLIENT)
                .capabilities(Set.of(Capability.SEND_MESSAGE))
                .premiumStatus(new PremiumStatus(false, null, false, null, false))
                .build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        var json = mapper.readTree(mapper.writeValueAsString(profile));

        assertThat(json.path("accountRole").asText()).isEqualTo("CLIENT");
        assertThat(json.path("capabilities").toString()).contains("SEND_MESSAGE");
        assertThat(json.has("premium")).isTrue();
        assertThat(json.has("premiumStatus")).isFalse();
    }
}
