package com.dunghaiquyen.ecommerce.modules.returns.dto;

import com.dunghaiquyen.ecommerce.modules.returns.entity.RefundProvider;
import com.dunghaiquyen.ecommerce.modules.returns.entity.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID returnId,
        UUID orderId,
        UUID paymentId,
        String refundCode,
        BigDecimal amount,
        RefundProvider provider,
        RefundStatus status,
        String gatewayRequestId,
        String gatewayTransactionNo,
        String manualReference,
        String manualNote,
        String refundBankCode,
        String refundBankName,
        String refundAccountNumber,
        String refundAccountName,
        String reason,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt) {
}
