package com.dunghaiquyen.ecommerce.modules.returns.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateRefundDestinationRequest(
        @NotBlank @Size(min = 2, max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String bankCode,
        @NotBlank @Size(min = 2, max = 100) String bankName,
        @NotBlank @Size(min = 6, max = 30) @Pattern(regexp = "^[0-9]+$") String accountNumber,
        @NotBlank @Size(min = 2, max = 100) String accountName) {
}
