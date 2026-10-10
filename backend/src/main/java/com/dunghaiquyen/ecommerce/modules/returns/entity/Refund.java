package com.dunghaiquyen.ecommerce.modules.returns.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/**
 * returnRequest is nullable because cancellation refunds belong directly to
 * an order; post-delivery merchandise refunds reference a Return.
 */
@Getter
@Setter
@Entity
@Table(name = "refunds")
public class Refund extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private Return returnRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "refund_code", nullable = false, unique = true, length = 50)
    private String refundCode;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "shipping_refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal shippingRefundAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "gateway_request_id", unique = true, length = 32)
    private String gatewayRequestId;

    @Column(name = "gateway_transaction_no", length = 100)
    private String gatewayTransactionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_response_json", columnDefinition = "jsonb")
    private Map<String, Object> gatewayResponseJson;

    @Column(name = "manual_reference", length = 100)
    private String manualReference;

    @Column(name = "manual_note", columnDefinition = "text")
    private String manualNote;
}
