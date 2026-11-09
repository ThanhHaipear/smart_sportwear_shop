package com.dunghaiquyen.ecommerce.modules.address.api;

/** Immutable Identity-owned address data captured by Sales when an order is created. */
public record AddressSnapshot(
        String receiverName,
        String phone,
        String province,
        String district,
        String ward,
        String addressLine) {
}
