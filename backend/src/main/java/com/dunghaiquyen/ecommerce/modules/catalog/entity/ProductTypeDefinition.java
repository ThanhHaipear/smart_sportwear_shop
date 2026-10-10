package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "product_types")
public class ProductTypeDefinition extends AbstractAuditEntity {
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 150) private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "default_size_system_id", nullable = false) private SizeSystem defaultSizeSystem;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LookupStatus status = LookupStatus.ACTIVE;
}
