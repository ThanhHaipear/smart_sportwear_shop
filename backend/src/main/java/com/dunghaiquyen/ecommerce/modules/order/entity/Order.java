package com.dunghaiquyen.ecommerce.modules.order.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductDataSource;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Aggregate root for order_items: items are written once at order creation in the
 * same transaction as stock reservation and never edited afterwards (snapshot semantics).
 * orderStatus and paymentStatus are intentionally separate state machines.
 */
@Getter
@Setter
@Entity
@Table(name = "orders")
public class Order extends AbstractAuditEntity {

    @Column(name = "order_code", nullable = false, unique = true, length = 50)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> addressSnapshotJson;

    @Column(name = "subtotal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "shipping_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 30)
    private OrderStatus orderStatus = OrderStatus.PENDING_CONFIRMATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "internal_note", columnDefinition = "text")
    private String internalNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_requested_by", length = 20)
    private CancellationRequestedBy cancellationRequestedBy;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 20)
    private ProductDataSource dataSource = ProductDataSource.REAL;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt = Instant.now();

    /** Set once, when orderStatus first reaches DELIVERED (see OrderService.applyStatusTransition) - backs the returns module's return-window eligibility check. Null for orders delivered before this column existed. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "return_eligible_until")
    private Instant returnEligibleUntil;

    /** Assigned once, lazily, the first time the order becomes invoice-eligible and someone actually asks for it - see OrderService.getOrderInvoice. Null until then. */
    @Column(name = "invoice_number", unique = true, length = 30)
    private String invoiceNumber;

    /** Whether the customer asked for a company (VAT-style) invoice at checkout, as opposed to the default personal receipt. */
    @Column(name = "invoice_requested", nullable = false)
    private boolean invoiceRequested = false;

    @Column(name = "invoice_company_name", length = 200)
    private String invoiceCompanyName;

    @Column(name = "invoice_tax_code", length = 50)
    private String invoiceTaxCode;

    @Column(name = "invoice_company_address", length = 255)
    private String invoiceCompanyAddress;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();
}
