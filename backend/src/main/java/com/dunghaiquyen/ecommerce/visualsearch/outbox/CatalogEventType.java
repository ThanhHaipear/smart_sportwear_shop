package com.dunghaiquyen.ecommerce.visualsearch.outbox;

public enum CatalogEventType {
    PRODUCT_IMAGE_CREATED("product.image.created"),
    PRODUCT_IMAGE_DELETED("product.image.deleted"),
    PRODUCT_ACTIVATED("product.activated"),
    PRODUCT_DEACTIVATED("product.deactivated"),
    PRODUCT_DELETED("product.deleted"),
    PRODUCT_REINDEX_REQUESTED("product.reindex.requested");

    private final String routingKey;

    CatalogEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
