package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetItem;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetItemId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogDatasetItemRepository
        extends JpaRepository<CatalogDatasetItem, CatalogDatasetItemId> {
    boolean existsByDatasetId(java.util.UUID datasetId);
}
