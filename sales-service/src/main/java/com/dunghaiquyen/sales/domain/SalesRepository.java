package com.dunghaiquyen.sales.domain;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SalesRepository {
    OrderResponse createOrder(OrderAggregate order);
    void cancelOrderAfterReservationFailure(UUID orderId, UUID paymentId, String reason);
    Optional<IngestionResult> findIngestion(String externalId);
    UUID insertBankTransaction(String externalId, String bankReference, String accountNumber, BigDecimal amount,
                               String description, Instant transactionTime, BigDecimal balance, Map<String,Object> payload);
    Optional<PaymentMatch> lockPayment(String transactionRef);
    Optional<RefundMatch> lockRefund(String refundCode);
    void matchPayment(UUID bankTransactionId, PaymentMatch payment, SePayRequest request, Instant at);
    void completeRefund(UUID bankTransactionId, RefundMatch refund, SePayRequest request, Instant at);
    void rejectTransaction(UUID bankTransactionId, MatchStatus status, String reason, UUID paymentId, UUID refundId);
    RefundResponse createRefund(CreateRefundRequest request, UUID actorId);
}
