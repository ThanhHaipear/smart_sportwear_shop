package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import java.io.Serializable;
import java.util.UUID;

public record CatalogDatasetItemId(UUID dataset, UUID product) implements Serializable {}
