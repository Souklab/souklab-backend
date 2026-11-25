package com.project.souklab.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexingDependency;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ObjectPath;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyValue;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "uk_users_email", columnList = "email", unique = true),
        @Index(name = "idx_users_status", columnList = "status, deleted_at"),
        @Index(name = "idx_users_phone", columnList = "phone")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    private String password;

    @FullTextField(analyzer = "artisanal_name")
    @Column(name = "first_name", length = 100)
    private String firstName;

    @FullTextField(analyzer = "artisanal_name")
    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(length = 30)
    private String phone;

    @KeywordField
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AccountStatus status = AccountStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_role", nullable = false, length = 20)
    @Builder.Default
    private AccountRole accountRole = AccountRole.CLIENT;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "last_oauth_login_at")
    private LocalDateTime lastOAuthLoginAt;

    @Column(name = "banned_until")
    private LocalDateTime bannedUntil;

    @Column(name = "ban_reason", columnDefinition = "TEXT")
    private String banReason;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_permissions",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    @Builder.Default
    private Set<AuthorizationPermission> permissions = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<OAuthIdentity> oauthIdentities = new HashSet<>();

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Artisan artisan;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Client client;

    @Transient
    @FullTextField(analyzer = "artisanal_name")
    @IndexingDependency(derivedFrom = {
        @ObjectPath(@PropertyValue(propertyName = "firstName")),
        @ObjectPath(@PropertyValue(propertyName = "lastName")),
        @ObjectPath(@PropertyValue(propertyName = "email"))
    })
    public String getName() {
        if (firstName != null && lastName != null) {
            return firstName.trim() + " " + lastName.trim();
        } else if (firstName != null) {
            return firstName.trim();
        } else if (lastName != null) {
            return lastName.trim();
        }
        return email;
    }

    /** Public fallback that never exposes the account email address.
     * Returns {@code "firstName L."} (first character of last name, uppercased).
     * Falls back to {@code "Member #XXXXX"} when name fields are empty. */
    @Transient
    public String getPublicDisplayName() {
        String fn = firstName != null ? firstName.trim() : null;
        String ln = lastName  != null ? lastName.trim()  : null;
        if (fn != null && !fn.isBlank() && ln != null && !ln.isBlank()) {
            return fn + " " + Character.toUpperCase(ln.charAt(0)) + ".";
        }
        if (fn != null && !fn.isBlank()) return fn;
        String id = getId() == null ? "00000" : getId().replace("-", "");
        return "Member #" + id.substring(Math.max(0, id.length() - 5)).toUpperCase(Locale.ROOT);
    }

    /**
     * Determines whether this user has an active suspension as of the specified point in time.
     * A suspension is considered active if the status is {@link AccountStatus#SUSPENDED}
     * and the ban is either permanent (bannedUntil is null) or the timeout expiration timestamp
     * is strictly in the future.
     *
     * @param now the reference point in time
     * @return true if the user is actively suspended, false otherwise
     */
    public boolean isSuspensionActive(LocalDateTime now) {
        return status == AccountStatus.SUSPENDED && (bannedUntil == null || bannedUntil.isAfter(now));
    }

    /**
     * Computes the effective account status as of the specified point in time.
     * If the persisted status is {@link AccountStatus#SUSPENDED} but the timeout has expired,
     * this returns {@link AccountStatus#ACTIVE}. Otherwise, returns the persisted status.
     *
     * @param now the reference point in time
     * @return the effective {@link AccountStatus}
     */
    public AccountStatus getEffectiveStatus(LocalDateTime now) {
        if (status == AccountStatus.SUSPENDED && !isSuspensionActive(now)) {
            return AccountStatus.ACTIVE;
        }
        return status;
    }

    public Set<AuthorizationPermission> getPermissions() {
        return permissions == null ? Collections.emptySet() : Collections.unmodifiableSet(permissions);
    }

    public boolean addPermission(AuthorizationPermission permission) {
        if (permissions == null) {
            permissions = new HashSet<>();
        }
        return permissions.add(permission);
    }

    public boolean removePermission(AuthorizationPermission permission) {
        if (permissions == null) {
            return false;
        }
        return permissions.remove(permission);
    }

    public void clearPermissions() {
        if (permissions != null) {
            permissions.clear();
        }
    }
}
