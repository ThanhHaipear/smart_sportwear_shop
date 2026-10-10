package com.dunghaiquyen.ecommerce.modules.inventory.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractCreatedAtEntity;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.returns.entity.Return;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Append-only audit trail. Every write to ProductVariant.stockQuantity /
 * reservedQuantity must be paired with exactly one row here, written by
 * InventoryService in the same transaction as the stock change.
 */
@Getter
@Setter
@Entity
@Table(name = "inventory_transactions")
public class InventoryTransaction extends AbstractCreatedAtEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private Return returnRequest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InventoryTransactionType type;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "before_stock_quantity", nullable = false)
    private int beforeStockQuantity;

    @Column(name = "after_stock_quantity", nullable = false)
    private int afterStockQuantity;

    @Column(name = "before_reserved_quantity", nullable = false)
    private int beforeReservedQuantity;

    @Column(name = "after_reserved_quantity", nullable = false)
    private int afterReservedQuantity;

    @Column(name = "reason", columnDefinition = "text")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User createdBy;

    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;
}
