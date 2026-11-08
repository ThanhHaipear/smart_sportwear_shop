package com.dunghaiquyen.ecommerce.modules.order.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A paid VietQR cancellation also carries the customer's manual-refund
 * destination. The amount is deliberately absent and is always derived from
 * the paid Payment row on the server.
 */
public record CancelOrderRequest(
        @Size(max = 500) String reason,
        @Size(min = 2, max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String bankCode,
        @Size(min = 2, max = 100) String bankName,
        @Size(min = 6, max = 30) @Pattern(regexp = "^[0-9]+$") String accountNumber,
        @Size(min = 2, max = 100) String accountName) {
}
