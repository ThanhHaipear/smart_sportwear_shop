package com.dunghaiquyen.ecommerce.modules.product.dto;

import java.util.UUID;

/**
 * Separate from ProductImageResponse on purpose: width/height are returned by
 * Cloudinary in the upload response and are also persisted as provenance for
 * publication checks and the later image-embedding pipeline.
 */
public record ProductImageUploadResponse(
        UUID id,
        String imageUrl,
        String publicId,
        String altText,
        String color,
        boolean isPrimary,
        int sortOrder,
        Integer width,
        Integer height) {
}
