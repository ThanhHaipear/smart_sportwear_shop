package com.dunghaiquyen.ecommerce.modules.returns.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractCreatedAtEntity;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderItem;
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
import lombok.Getter;
import lombok.Setter;

/**
 * No updated_at (V3's schema, mirrors OrderItem's own "snapshot, not edited
 * after the fact" shape) - conditionStatus/resolution/refundAmount are only
 * ever set ONCE, when the admin marks the parent Return RECEIVED (see
 * ReturnService.updateStatus), not edited again afterward.
 */
@Getter
@Setter
@Entity
@Table(name = "return_items")
public class ReturnItem extends AbstractCreatedAtEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private Return returnRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 100)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_status", length = 30)
    private ReturnItemConditionStatus conditionStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReturnItemResolution resolution;

    @Column(name = "refund_amount", precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Column(nullable = false)
    private boolean restocked;
}
