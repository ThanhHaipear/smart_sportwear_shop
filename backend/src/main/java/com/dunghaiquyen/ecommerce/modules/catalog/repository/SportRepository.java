package com.dunghaiquyen.ecommerce.modules.catalog.repository;

import com.dunghaiquyen.ecommerce.modules.catalog.entity.Sport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SportRepository extends JpaRepository<Sport, UUID> {
    Optional<Sport> findByCodeIgnoreCase(String code);
    Optional<Sport> findByNameIgnoreCase(String name);
}
