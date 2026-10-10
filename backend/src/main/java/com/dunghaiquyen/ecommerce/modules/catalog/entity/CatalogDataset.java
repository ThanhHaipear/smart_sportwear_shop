package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractCreatedAtEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "catalog_datasets")
public class CatalogDataset extends AbstractCreatedAtEntity {
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 50) private String version;
    @Column(name = "manifest_uri", nullable = false, columnDefinition = "text") private String manifestUri;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "manifest_checksum", nullable = false, length = 64, columnDefinition = "char(64)")
    private String manifestChecksum;
    @Column(name = "source_description", columnDefinition = "text") private String sourceDescription;
    @Column(name = "license_code", nullable = false, length = 50) private String licenseCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CatalogDatasetStatus status = CatalogDatasetStatus.DRAFT;
    @Column(name = "imported_at") private Instant importedAt;
}
