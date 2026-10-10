package com.dunghaiquyen.ecommerce.modules.catalog.entity;

import java.io.Serializable;
import java.util.UUID;

public record ProductMaterialId(UUID product, UUID material) implements Serializable {}
