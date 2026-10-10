package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductTypeDefinition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTypeDefinitionRepository extends JpaRepository<ProductTypeDefinition, UUID> {
    Optional<ProductTypeDefinition> findByCodeIgnoreCase(String code);
}
