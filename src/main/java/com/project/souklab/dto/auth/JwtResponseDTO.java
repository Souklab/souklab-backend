package com.project.souklab.dto.auth;

import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.security.Permission;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.model.AccountRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtResponseDTO {
    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private TokenType tokenType = TokenType.BEARER;
    private long expiresIn;
    /** Account-type profile object: ClientProfileResponseDTO for clients, ArtisanResponseDTO for artisans. */
    private ProfileResponse user;
    private List<Permission> permissions;
    private AccountRole accountRole;
    private Set<Capability> capabilities;
    private PremiumStatus premium;
}
