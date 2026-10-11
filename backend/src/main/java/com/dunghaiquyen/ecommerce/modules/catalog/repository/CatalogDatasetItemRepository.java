package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetItem;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetItemId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogDatasetItemRepository
        extends JpaRepository<CatalogDatasetItem, CatalogDatasetItemId> {
    boolean existsByDatasetId(java.util.UUID datasetId);
    boolean existsByDatasetIdAndProductId(java.util.UUID datasetId, java.util.UUID productId);
    long countByDatasetId(java.util.UUID datasetId);
}
