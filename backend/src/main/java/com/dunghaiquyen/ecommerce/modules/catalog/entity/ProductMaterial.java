package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "product_materials")
@IdClass(ProductMaterialId.class)
public class ProductMaterial {
    @Id @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Id @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false) private Material material;
    @Column(precision = 5, scale = 2) private BigDecimal percentage;
}
