package com.project.souklab.dto.profile;

import com.project.souklab.model.AccountRole;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.security.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Minimal current-user profile for administrators, who do not have a client profile. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileResponseDTO implements ProfileResponse {
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String name;
    private String phone;
    private String avatarUrl;
    private AccountStatus accountStatus;
    private AccountRole accountRole;
    private Set<Capability> capabilities;
    @JsonProperty("premium")
    private PremiumStatus premiumStatus;
    private Set<Permission> permissions;
    private boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
