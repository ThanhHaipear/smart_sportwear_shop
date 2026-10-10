package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.SizeSystem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SizeSystemRepository extends JpaRepository<SizeSystem, UUID> {
    Optional<SizeSystem> findByCodeIgnoreCase(String code);
}
