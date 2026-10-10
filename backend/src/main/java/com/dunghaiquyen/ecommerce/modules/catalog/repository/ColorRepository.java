package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.Color;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ColorRepository extends JpaRepository<Color, UUID> {
    Optional<Color> findByCodeIgnoreCase(String code);
    Optional<Color> findByNameIgnoreCase(String name);
}
