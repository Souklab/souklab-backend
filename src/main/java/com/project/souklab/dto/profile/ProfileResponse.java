package com.project.souklab.dto.profile;

import com.project.souklab.security.Permission;

import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;

import java.time.LocalDateTime;
import java.util.Set;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Common response contract for user profiles (Client and Artisan).
 * Provides compile-time type safety across Auth and Profile endpoints while
 * allowing polymorphic responses without leaking account-type-specific fields.
 */
@Schema(oneOf = {ClientProfileResponseDTO.class, ArtisanResponseDTO.class, AdminProfileResponseDTO.class})
public interface ProfileResponse {

    String getId();

    String getEmail();

    String getFirstName();

    String getLastName();

    String getName();

    String getPhone();

    String getAvatarUrl();

    AccountStatus getAccountStatus();

    AccountRole getAccountRole();

    Set<Capability> getCapabilities();

    PremiumStatus getPremiumStatus();

    Set<Permission> getPermissions();

    boolean isEmailVerified();

    LocalDateTime getEmailVerifiedAt();

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();
}
