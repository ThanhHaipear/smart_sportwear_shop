package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDataset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogDatasetRepository extends JpaRepository<CatalogDataset, UUID> {
    Optional<CatalogDataset> findByNameAndVersion(String name, String version);
}
