package com.project.souklab.dto.profile;

import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.ClientType;
import com.project.souklab.security.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;

/**
 * Profile response DTO scoped to CLIENT users.
 * Deliberately excludes any artisan-specific fields (teacher, premium-as-artisan-premium, etc.)
 * so that client users never see those in their own profile responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientProfileResponseDTO implements ProfileResponse {

    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String name;
    private String phone;
    /** Placeholder — wired to real upload path in Phase D. */
    private String avatarUrl;
    private AccountStatus accountStatus;
    private AccountRole accountRole;
    private Set<Capability> capabilities;
    private PremiumStatus premiumStatus;
    private Set<Permission> permissions;
    private boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private ClientType clientType;
    private String companyName;
    private String bio;
    private String address;
    private String regionId;
    private String city;

    @Override
    public String getId() { return id; }

    @Override
    public String getEmail() { return email; }

    @Override
    public String getFirstName() { return firstName; }

    @Override
    public String getLastName() { return lastName; }

    @Override
    public String getName() { return name; }

    @Override
    public String getPhone() { return phone; }

    @Override
    public String getAvatarUrl() { return avatarUrl; }

    @Override
    public AccountStatus getAccountStatus() { return accountStatus; }

    @Override
    public Set<Permission> getPermissions() { return permissions; }

    @Override
    public boolean isEmailVerified() { return emailVerified; }

    @Override
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }

    @Override
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
