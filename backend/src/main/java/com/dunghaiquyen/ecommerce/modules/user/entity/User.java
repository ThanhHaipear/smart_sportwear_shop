package com.dunghaiquyen.ecommerce.modules.user.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends AbstractAuditEntity {

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    /** Null for Google-authenticated users (see LoginProvider.GOOGLE). V9 made this column nullable. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    /** Compatibility helper; the persisted provider source of truth is user_identities. */
    @Transient
    public LoginProvider getLoginProvider() {
        return passwordHash != null ? LoginProvider.LOCAL : LoginProvider.GOOGLE;
    }

    /** Kept for legacy callers; provider is no longer persisted on users. */
    public void setLoginProvider(LoginProvider loginProvider) {
        if (loginProvider == LoginProvider.GOOGLE) {
            this.passwordHash = null;
        }
    }
}
