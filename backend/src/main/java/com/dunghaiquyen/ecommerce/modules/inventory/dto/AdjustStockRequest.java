package com.dunghaiquyen.ecommerce.modules.inventory.dto;

import com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * {@code type} is validated in the service to be one of ADJUSTMENT_UP/
 * ADJUSTMENT_DOWN only - this endpoint must not be usable to forge an
 * IMPORT/EXPORT/ORDER_* row under a different code path (Bean Validation has
 * no clean way to restrict an enum to a subset, so this is a service-layer check).
 */
public record AdjustStockRequest(
        UUID requestId,
        @NotNull(message = "Variant is required") UUID variantId,
        @NotNull(message = "Adjustment type is required") InventoryTransactionType type,
        @Positive(message = "Quantity must be greater than 0") int quantity,
        @jakarta.validation.constraints.NotBlank(message = "Reason is required") String note) {
}
