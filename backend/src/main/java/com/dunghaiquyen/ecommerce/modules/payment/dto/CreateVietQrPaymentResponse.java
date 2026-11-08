package com.dunghaiquyen.ecommerce.modules.payment.dto;

import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateVietQrPaymentResponse(
        UUID paymentId,
        UUID orderId,
        String transactionRef,
        BigDecimal amount,
        String currency,
        String bankBin,
        String accountNumber,
        String accountName,
        PaymentStatus status,
        Instant expiresAt) {
}
