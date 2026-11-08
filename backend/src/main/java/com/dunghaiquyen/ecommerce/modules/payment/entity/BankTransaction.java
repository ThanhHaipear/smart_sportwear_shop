package com.dunghaiquyen.ecommerce.modules.payment.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.returns.entity.Refund;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "bank_transactions")
public class BankTransaction extends AbstractAuditEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private BankTransactionSource source;

    @Column(name = "external_transaction_id", length = 100, updatable = false)
    private String externalTransactionId;

    @Column(name = "external_transaction_uuid", updatable = false)
    private UUID externalTransactionUuid;

    @Column(name = "bank_reference", length = 150, updatable = false)
    private String bankReference;

    @Column(name = "account_number", nullable = false, length = 50, updatable = false)
    private String accountNumber;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(columnDefinition = "text", updatable = false)
    private String description;

    @Column(name = "transaction_time", nullable = false, updatable = false)
    private Instant transactionTime;

    @Column(name = "running_balance", precision = 18, scale = 2, updatable = false)
    private BigDecimal runningBalance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private Refund refund;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 30)
    private BankTransactionMatchStatus matchStatus = BankTransactionMatchStatus.RECEIVED;

    @Column(name = "match_reason", length = 255)
    private String matchReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload_redacted", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> rawPayloadRedacted;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "reconciled_at")
    private Instant reconciledAt;
}
