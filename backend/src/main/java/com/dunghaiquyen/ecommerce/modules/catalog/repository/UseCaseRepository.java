package com.dunghaiquyen.ecommerce.modules.catalog.repository;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.UseCase;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UseCaseRepository extends JpaRepository<UseCase, UUID> {}
