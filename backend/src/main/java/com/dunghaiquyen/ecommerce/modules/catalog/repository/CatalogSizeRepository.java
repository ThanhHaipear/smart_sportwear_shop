package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogSize;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogSizeRepository extends JpaRepository<CatalogSize, UUID> {
    Optional<CatalogSize> findBySizeSystemIdAndCodeIgnoreCase(UUID sizeSystemId, String code);
    Optional<CatalogSize> findBySizeSystemIdAndDisplayNameIgnoreCase(UUID sizeSystemId, String displayName);
}
