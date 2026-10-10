package com.dunghaiquyen.ecommerce.modules.product.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogSize;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Color;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Sole owner of stock_quantity/reserved_quantity is InventoryService — this entity
 * only exposes the fields, it must never be saved directly by other modules to change stock.
 * {@code version} is an optimistic-lock guard for metadata edits (price, status); stock
 * mutations still go through explicit pessimistic row locking in InventoryService.
 */
@Getter
@Setter
@Entity
@Table(name = "product_variants")
public class ProductVariant extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "size_id", nullable = false)
    private CatalogSize sizeDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "color_id", nullable = false)
    private Color colorDefinition;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 14, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint = 0;

    @Version
    @Column(nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VariantStatus status = VariantStatus.ACTIVE;

    @Transient
    public String getSize() {
        return sizeDefinition == null ? null : sizeDefinition.getDisplayName();
    }

    public void setSize(String ignored) {
        // Compatibility only. ProductVariantService resolves the size FK.
    }

    @Transient
    public String getColor() {
        return colorDefinition == null ? null : colorDefinition.getName();
    }

    public void setColor(String ignored) {
        // Compatibility only. ProductVariantService resolves the color FK.
    }
}
