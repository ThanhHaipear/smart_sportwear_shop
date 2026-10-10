package com.dunghaiquyen.ecommerce.modules.catalog.service;

import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogSize;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Color;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductTypeDefinition;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.Sport;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.CatalogSizeRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.ColorRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.ProductTypeDefinitionRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.SportRepository;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductType;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogReferenceService {
    private final ProductTypeDefinitionRepository productTypes;
    private final CatalogSizeRepository sizes;
    private final ColorRepository colors;
    private final SportRepository sports;

    public CatalogReferenceService(
            ProductTypeDefinitionRepository productTypes,
            CatalogSizeRepository sizes,
            ColorRepository colors,
            SportRepository sports) {
        this.productTypes = productTypes;
        this.sizes = sizes;
        this.colors = colors;
        this.sports = sports;
    }

    public ProductTypeDefinition requireProductType(ProductType type) {
        String code = (type != null ? type : ProductType.SPORTS_TOP).catalogCode();
        return productTypes.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ResourceNotFoundException("Product type not found: " + code));
    }

    public CatalogSize requireSize(ProductTypeDefinition type, String value) {
        String normalized = value.trim();
        return sizes.findBySizeSystemIdAndCodeIgnoreCase(type.getDefaultSizeSystem().getId(), normalized)
                .or(() -> sizes.findBySizeSystemIdAndDisplayNameIgnoreCase(
                        type.getDefaultSizeSystem().getId(), normalized))
                .orElseThrow(() -> new ResourceNotFoundException("Size not found in product size system: " + value));
    }

    public Color requireColor(String value) {
        String normalized = value.trim();
        return colors.findByCodeIgnoreCase(normalized)
                .or(() -> colors.findByNameIgnoreCase(normalized))
                .orElseThrow(() -> new ResourceNotFoundException("Color not found: " + value));
    }

    public Sport requireSport(String value) {
        String normalized = value.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        return sports.findByCodeIgnoreCase(normalized)
                .or(() -> sports.findByNameIgnoreCase(value.trim()))
                .orElseThrow(() -> new ResourceNotFoundException("Sport not found: " + value));
    }
}
