package com.dunghaiquyen.ecommerce.modules.payment.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
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
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The pair (provider, transactionRef) is unique. Callback handling locks the
 * matching row and no-ops after the terminal result has already been applied.
 * One order may have several payment rows (retries / re-created sessions).
 */
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "transaction_ref", nullable = false, length = 150)
    private String transactionRef;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload_json", columnDefinition = "jsonb")
    private Map<String, Object> rawPayloadJson;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "transaction_date", length = 14)
    private String transactionDate;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "gateway_transaction_no", length = 100)
    private String gatewayTransactionNo;

    @Column(name = "bank_code", length = 30)
    private String bankCode;
}
