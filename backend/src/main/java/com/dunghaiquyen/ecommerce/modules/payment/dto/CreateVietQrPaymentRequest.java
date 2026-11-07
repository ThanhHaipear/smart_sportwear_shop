package com.dunghaiquyen.ecommerce.modules.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateVietQrPaymentRequest(@NotNull(message = "Order is required") UUID orderId) {
}
