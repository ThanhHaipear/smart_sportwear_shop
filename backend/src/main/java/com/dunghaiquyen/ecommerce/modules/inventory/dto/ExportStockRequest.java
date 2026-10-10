package com.dunghaiquyen.ecommerce.modules.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ExportStockRequest(
        UUID requestId,
        @NotNull(message = "Variant is required") UUID variantId,
        @Positive(message = "Quantity must be greater than 0") int quantity,
        @NotBlank(message = "Reason is required") String note) {
}
