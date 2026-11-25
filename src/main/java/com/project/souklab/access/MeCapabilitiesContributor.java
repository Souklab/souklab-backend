package com.project.souklab.access;

import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.dto.profile.AdminProfileResponseDTO;
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import com.project.souklab.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Adds the canonical role, capability, and live premium snapshot to the current-user response. */
@Component
@RequiredArgsConstructor
public class MeCapabilitiesContributor {
    private final CapabilityService capabilityService;
    private final Clock clock;

    public ProfileResponse contribute(User user, ProfileResponse response) {
        if (response.getCapabilities() != null && response.getPremiumStatus() != null) {
            return response;
        }
        ViewerContext context = capabilityService.compute(user, Instant.now(clock));
        if (response instanceof AdminProfileResponseDTO admin) {
            admin.setCapabilities(context.capabilities());
            admin.setPremiumStatus(context.premiumStatus());
        } else if (response instanceof ArtisanResponseDTO artisan) {
            artisan.setCapabilities(context.capabilities());
            artisan.setPremiumStatus(context.premiumStatus());
        } else if (response instanceof ClientProfileResponseDTO client) {
            client.setCapabilities(context.capabilities());
            client.setPremiumStatus(context.premiumStatus());
        }
        return response;
    }
}
