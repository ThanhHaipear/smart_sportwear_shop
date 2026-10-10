package com.dunghaiquyen.ecommerce.modules.order.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractEntity;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * No timestamps by design (ERD_PHASE1.md 6.13): an order item is an immutable
 * snapshot written once when the order is created.
 */
@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(name = "product_name_snapshot", nullable = false, length = 255)
    private String productNameSnapshot;

    @Column(name = "sku_snapshot", nullable = false, length = 100)
    private String skuSnapshot;

    @Column(name = "size_snapshot", nullable = false, length = 50)
    private String sizeSnapshot;

    @Column(name = "color_snapshot", nullable = false, length = 50)
    private String colorSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "discount_allocated", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAllocated = BigDecimal.ZERO;

    @Column(name = "net_line_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal netLineAmount;

    @Column(name = "return_pending_quantity", nullable = false)
    private int returnPendingQuantity;

    @Column(name = "returned_quantity", nullable = false)
    private int returnedQuantity;
}
