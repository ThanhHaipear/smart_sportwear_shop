package com.dunghaiquyen.sales.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SalesModels {
    private SalesModels() {}
    public enum PaymentMethod { COD, VIETQR }
    public enum MatchStatus { MATCHED, UNMATCHED, AMOUNT_MISMATCH, LATE_PAYMENT, MANUAL_REVIEW, IGNORED_OUTGOING }
    public record CreateOrderRequest(@NotNull UUID userId, @NotEmpty List<@Valid OrderLineRequest> items,
                                     @NotNull PaymentMethod paymentMethod, @NotNull Map<String,Object> address,
                                     @PositiveOrZero BigDecimal shippingFee, String note) {}
    public record OrderLineRequest(@NotNull UUID variantId, @Positive int quantity) {}
    public record VariantSnapshot(UUID variantId, UUID productId, String sku, String productName, String size,
                                  String color, BigDecimal price, boolean active, int availableQuantity) {}
    public record Reservation(UUID id, Instant expiresAt) {}
    public record OrderLine(UUID id, UUID productId, UUID variantId, String sku, String productName, String size,
                            String color, BigDecimal unitPrice, int quantity) {
        public BigDecimal total() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
    }
    public record OrderAggregate(UUID id, String orderCode, UUID userId, UUID reservationId,
                                 PaymentMethod paymentMethod, BigDecimal subtotal, BigDecimal shippingFee,
                                 BigDecimal total, List<OrderLine> items, UUID paymentId, String transactionRef,
                                 Instant paymentExpiresAt, Map<String,Object> address, String note) {}
    public record OrderResponse(UUID id, String orderCode, String orderStatus, String paymentStatus,
                                PaymentMethod paymentMethod, BigDecimal total, String transactionRef,
                                Instant paymentExpiresAt) {}
    public record SePayRequest(@NotNull Long id, @NotBlank String gateway, @NotBlank String transactionDate,
                               @NotBlank String accountNumber, String code, String content, String description,
                               @NotBlank @Pattern(regexp="in|out") String transferType,
                               @NotNull @DecimalMin("0.01") BigDecimal transferAmount,
                               BigDecimal accumulated, String referenceCode) {}
    public record IngestionResult(UUID bankTransactionId, MatchStatus matchStatus, boolean alreadyProcessed) {}
    public record PaymentMatch(UUID paymentId, UUID orderId, BigDecimal amount, String status,
                               String orderStatus, String orderPaymentStatus, Instant expiresAt) {}
    public record RefundMatch(UUID refundId, UUID paymentId, UUID orderId, BigDecimal amount, String status,
                              boolean destinationComplete) {}
    public record CreateRefundRequest(@NotNull UUID orderId, @NotNull UUID paymentId,
                                      @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String reason,
                                      String bankCode, String bankName, String accountNumber, String accountName) {}
    public record RefundResponse(UUID id, String refundCode, BigDecimal amount, String status) {}
}
