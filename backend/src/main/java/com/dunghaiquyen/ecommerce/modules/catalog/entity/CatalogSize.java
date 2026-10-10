package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractCreatedAtEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "sizes")
public class CatalogSize extends AbstractCreatedAtEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "size_system_id", nullable = false) private SizeSystem sizeSystem;
    @Column(nullable = false, length = 30) private String code;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
}
