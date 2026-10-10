package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "use_cases")
public class UseCase extends AbstractAuditEntity {
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 150) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LookupStatus status = LookupStatus.ACTIVE;
}
