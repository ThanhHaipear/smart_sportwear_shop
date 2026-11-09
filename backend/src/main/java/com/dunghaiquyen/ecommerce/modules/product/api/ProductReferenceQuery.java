package com.dunghaiquyen.ecommerce.modules.product.api;

import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Transitional Catalog API while consumers still persist JPA references to Catalog entities. */
public interface ProductReferenceQuery {

    Optional<Product> findProduct(UUID productId);

    Optional<Product> findActiveProductWithBrandAndCategory(UUID productId);

    List<Product> findActiveProductsWithBrandAndCategory(Collection<UUID> productIds);

    List<Product> findSimilarActiveProducts(
            UUID categoryId, UUID brandId, UUID excludedProductId, Pageable pageable);

    List<Product> findActiveFallbackProductsExcluding(
            Collection<UUID> excludedProductIds, Pageable pageable);

    Optional<ProductVariant> findVariant(UUID variantId);

    Optional<ProductVariant> findVariantForUpdate(UUID variantId);

    List<ProductVariant> findVariantsByProductIds(List<UUID> productIds);

    List<ProductImage> findImagesByProductIds(List<UUID> productIds);
}
