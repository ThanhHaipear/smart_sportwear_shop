package com.dunghaiquyen.ecommerce.modules.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CatalogIndexDocumentResponse(
        UUID productId,
        long sourceVersion,
        String status,
        String name,
        String slug,
        String description,
        UUID categoryId,
        String category,
        String categorySlug,
        UUID brandId,
        String brand,
        String brandSlug,
        String productType,
        String gender,
        List<String> sportCodes,
        Map<String, String> attributes,
        List<Variant> variants,
        List<Image> images) {

    public record Variant(
            UUID id, String sku, String size, String color, String colorFamily,
            BigDecimal price, int availableQuantity, String status) {}

    public record Image(UUID id, String url, String publicId, int sortOrder) {}
}
