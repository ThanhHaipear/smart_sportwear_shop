package com.dunghaiquyen.identity.domain;

import java.time.Instant;
import java.util.UUID;

public final class IdentityModels {
    private IdentityModels() {}
    public enum Role { CUSTOMER, SALES_STAFF, WAREHOUSE_STAFF, ADMIN }
    public enum Status { ACTIVE, LOCKED, DEACTIVATED }
    public record User(UUID id, String fullName, String email, String phone, String passwordHash,
                       Role role, Status status, String loginProvider, long version) {}
    public record Address(UUID id, UUID userId, String receiverName, String phone, String province,
                          String district, String ward, String addressLine, boolean isDefault, Instant createdAt) {}
}
