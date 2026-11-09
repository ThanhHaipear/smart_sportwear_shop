package com.dunghaiquyen.ecommerce.modules.product.service;

import com.dunghaiquyen.ecommerce.modules.product.api.ProductReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductReferenceService implements ProductReferenceQuery {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;

    public ProductReferenceService(
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            ProductImageRepository imageRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findProduct(UUID productId) {
        return productRepository.findById(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findActiveProductWithBrandAndCategory(UUID productId) {
        return productRepository.findActiveByIdWithBrandAndCategory(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findActiveProductsWithBrandAndCategory(Collection<UUID> productIds) {
        return productRepository.findActiveByIdInWithBrandAndCategory(productIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findSimilarActiveProducts(
            UUID categoryId, UUID brandId, UUID excludedProductId, Pageable pageable) {
        return productRepository.findSimilarActiveProducts(categoryId, brandId, excludedProductId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findActiveFallbackProductsExcluding(
            Collection<UUID> excludedProductIds, Pageable pageable) {
        return productRepository.findActiveFallbackProductsExcluding(excludedProductIds, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductVariant> findVariant(UUID variantId) {
        return variantRepository.findById(variantId);
    }

    @Override
    @Transactional
    public Optional<ProductVariant> findVariantForUpdate(UUID variantId) {
        return variantRepository.findByIdForUpdate(variantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductVariant> findVariantsByProductIds(List<UUID> productIds) {
        return variantRepository.findAllByProductIdIn(productIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductImage> findImagesByProductIds(List<UUID> productIds) {
        return imageRepository.findAllByProductIdIn(productIds);
    }
}
