package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductMaterial;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductMaterialId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductMaterialRepository extends JpaRepository<ProductMaterial, ProductMaterialId> {}
