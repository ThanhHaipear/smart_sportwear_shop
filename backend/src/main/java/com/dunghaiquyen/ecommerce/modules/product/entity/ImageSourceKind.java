package com.dunghaiquyen.ecommerce.modules.product.entity;

public enum ImageSourceKind {
    OWNED,
    LICENSED,
    SYNTHETIC,

    /**
     * Image copied from a declared external source for a private educational
     * dataset. This value records provenance; it does not assert ownership or
     * a commercial redistribution licence.
     */
    REFERENCE
}
