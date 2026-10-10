package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "colors")
public class Color extends AbstractAuditEntity {
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 150) private String name;
    @Column(name = "family_code", nullable = false, length = 30) private String familyCode;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "hex_value", length = 7, columnDefinition = "char(7)") private String hexValue;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LookupStatus status = LookupStatus.ACTIVE;
}
