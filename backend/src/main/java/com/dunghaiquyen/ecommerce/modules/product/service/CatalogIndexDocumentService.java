package com.dunghaiquyen.ecommerce.modules.product.service;

import com.dunghaiquyen.ecommerce.modules.product.api.CatalogIndexDocumentResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CatalogIndexDocumentService {
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public CatalogIndexDocumentResponse find(UUID productId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) return null;
        return new CatalogIndexDocumentResponse(
                product.getId(), product.getCatalogVersion(), product.getStatus().name(),
                product.getName(), product.getDescription() == null ? product.getShortDescription() : product.getDescription(),
                product.getCategory().getId(), product.getCategory().getName(), product.getCategory().getSlug(),
                product.getBrand().getId(), product.getBrand().getName(), product.getBrand().getSlug(),
                product.getProductTypeDefinition().getCode(), product.getGender().name(),
                product.getSports().stream().map(sport -> sport.getCode()).sorted().toList(),
                product.getAttributes(),
                product.getVariants().stream().map(variant -> new CatalogIndexDocumentResponse.Variant(
                        variant.getId(), variant.getSku(), variant.getSize(), variant.getColor(),
                        variant.getColorDefinition().getFamilyCode(), variant.getPrice(),
                        Math.max(0, variant.getStockQuantity() - variant.getReservedQuantity()),
                        variant.getStatus().name())).toList(),
                product.getImages().stream().map(image -> new CatalogIndexDocumentResponse.Image(
                        image.getId(), image.getSecureUrl(), image.getPublicId(), image.getSortOrder())).toList());
    }
}
