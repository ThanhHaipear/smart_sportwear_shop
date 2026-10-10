package com.dunghaiquyen.ecommerce.modules.auth.entity;

public enum RefreshTokenRevocationReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED,
    PASSWORD_RESET,
    ACCOUNT_LOCKED,
    ACCOUNT_DEACTIVATED,
    ADMIN_REVOKED
}
