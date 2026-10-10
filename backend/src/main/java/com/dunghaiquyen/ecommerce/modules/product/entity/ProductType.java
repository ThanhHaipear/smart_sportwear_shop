package com.dunghaiquyen.ecommerce.modules.product.entity;

/** API-facing product type mapped to the five mandatory product_types rows in schema 1A. */
public enum ProductType {
    SPORTS_TOP("SPORTS_TOP"),
    SPORTS_BOTTOM("SPORTS_BOTTOM"),
    SPORTS_SHOES("SPORTS_SHOES"),
    SPORTS_UNIFORM("SPORTS_UNIFORM"),
    SPORTS_ACCESSORY("SPORTS_ACCESSORY"),

    /** Legacy API aliases retained while clients migrate to the five 1A types. */
    APPAREL("SPORTS_TOP"),
    FOOTWEAR("SPORTS_SHOES"),
    ACCESSORY("SPORTS_ACCESSORY"),
    EQUIPMENT("SPORTS_ACCESSORY");

    private final String catalogCode;

    ProductType(String catalogCode) {
        this.catalogCode = catalogCode;
    }

    public String catalogCode() {
        return catalogCode;
    }

    public static ProductType fromCatalogCode(String code) {
        return switch (code) {
            case "SPORTS_TOP" -> SPORTS_TOP;
            case "SPORTS_BOTTOM" -> SPORTS_BOTTOM;
            case "SPORTS_SHOES" -> SPORTS_SHOES;
            case "SPORTS_UNIFORM" -> SPORTS_UNIFORM;
            case "SPORTS_ACCESSORY" -> SPORTS_ACCESSORY;
            default -> throw new IllegalArgumentException("Unknown catalog product type: " + code);
        };
    }
}
