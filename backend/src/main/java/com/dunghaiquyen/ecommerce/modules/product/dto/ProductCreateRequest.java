package com.dunghaiquyen.ecommerce.modules.product.dto;

import com.dunghaiquyen.ecommerce.common.validation.Patterns;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductDataSource;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record ProductCreateRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @NotBlank(message = "Slug is required")
        @Size(max = 300, message = "Slug must be at most 300 characters")
        @Pattern(regexp = Patterns.SLUG, message = "Slug must be lowercase letters, numbers and hyphens only")
        String slug,

        @Size(max = 500, message = "Short description must be at most 500 characters")
        String shortDescription,

        String description,

        @NotNull(message = "Category is required")
        UUID categoryId,

        @NotNull(message = "Brand is required")
        UUID brandId,

        Gender gender,

        @Size(max = 50, message = "Sport type must be at most 50 characters")
        String sportType,

        /** Catalog V2 (V10): optional at creation time, null is accepted for backward compat. */
        com.dunghaiquyen.ecommerce.modules.product.entity.ProductType productType,

        ProductStatus status,

        Boolean isFeatured,

        Map<String, String> attributes,

        ProductDataSource dataSource) {
}
