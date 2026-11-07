package com.dunghaiquyen.ecommerce.modules.payment.sepay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

public record SePayWebhookRequest(
        @NotNull @PositiveOrZero Long id,
        @Size(max = 100) String gateway,
        @NotBlank @Size(max = 30) String transactionDate,
        @NotBlank @Size(max = 50) String accountNumber,
        @Size(max = 100) String subAccount,
        @Size(max = 255) String code,
        @Size(max = 2000) String content,
        @NotBlank @Pattern(regexp = "in|out") String transferType,
        @Size(max = 2000) String description,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal transferAmount,
        @Digits(integer = 16, fraction = 2) BigDecimal accumulated,
        @Size(max = 150) String referenceCode) {
}
