package com.dunghaiquyen.ecommerce.modules.address.repository;

import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    boolean existsByIdAndUserId(UUID id, UUID userId);

    Optional<Address> findFirstByUserIdOrderByCreatedAtAsc(UUID userId);

    List<Address> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    // Explicit JPQL rather than a derived "...AndIsDefaultTrue" method name -
    // the field is named isDefault (not "default"), and spelling that out as a
    // direct property path here sidesteps any "is"-prefix ambiguity in Spring
    // Data's derived-query name parser.
    @Query("select a from Address a where a.user.id = :userId and a.isDefault = true")
    List<Address> findAllByUserIdAndIsDefaultTrue(@Param("userId") UUID userId);
}
