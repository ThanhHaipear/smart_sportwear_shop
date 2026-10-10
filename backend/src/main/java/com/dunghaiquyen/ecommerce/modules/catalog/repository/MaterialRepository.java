package com.dunghaiquyen.ecommerce.modules.catalog.repository;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Material;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MaterialRepository extends JpaRepository<Material, UUID> {}
