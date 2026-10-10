package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "catalog_dataset_items")
@IdClass(CatalogDatasetItemId.class)
public class CatalogDatasetItem {
    @Id @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false) private CatalogDataset dataset;
    @Id @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Column(name = "source_record_id", nullable = false, length = 150) private String sourceRecordId;
    @Column(name = "source_uri", columnDefinition = "text") private String sourceUri;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "normalized_checksum", nullable = false, length = 64, columnDefinition = "char(64)")
    private String normalizedChecksum;
    @Column(name = "imported_at", nullable = false) private Instant importedAt;
}
