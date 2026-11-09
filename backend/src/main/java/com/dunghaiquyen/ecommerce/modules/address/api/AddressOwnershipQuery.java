package com.dunghaiquyen.ecommerce.modules.address.api;

import java.util.UUID;

/**
 * Public Identity boundary used by other bounded contexts to validate address ownership
 * without depending on Identity entities or repositories.
 */
public interface AddressOwnershipQuery {

    void requireOwnedAddress(UUID userId, UUID addressId);

    AddressSnapshot getOwnedAddress(UUID userId, UUID addressId);
}
