package com.dunghaiquyen.ecommerce.modules.product.repository;

import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    Optional<Product> findBySlugAndStatus(String slug, ProductStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);
    @Query("""
        select p
        from Product p
        join fetch p.brand
        join fetch p.category
        where p.id = :id
          and p.status = com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus.ACTIVE
    """)
    Optional<Product> findActiveByIdWithBrandAndCategory(@Param("id") UUID id);

    @Query("""
        select distinct p
        from Product p
        join fetch p.brand
        join fetch p.category
        where p.id in :ids
          and p.status = com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus.ACTIVE
    """)
    List<Product> findActiveByIdInWithBrandAndCategory(@Param("ids") Collection<UUID> ids);

    @Query("""
        select distinct p
        from Product p
        join fetch p.brand
        join fetch p.category
        where p.status = com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus.ACTIVE
          and p.id <> :excludedProductId
          and (
                p.category.id = :categoryId
                or p.brand.id = :brandId
          )
        order by p.createdAt desc, p.id asc
    """)
    List<Product> findSimilarActiveProducts(
            @Param("categoryId") UUID categoryId,
            @Param("brandId") UUID brandId,
            @Param("excludedProductId") UUID excludedProductId,
            Pageable pageable
    );

    @Query("""
        select distinct p
        from Product p
        join fetch p.brand
        join fetch p.category
        where p.status = com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus.ACTIVE
          and p.id not in :excludedProductIds
        order by p.createdAt desc, p.id asc
    """)
    List<Product> findActiveFallbackProductsExcluding(
            @Param("excludedProductIds") Collection<UUID> excludedProductIds,
            Pageable pageable
    );
}
