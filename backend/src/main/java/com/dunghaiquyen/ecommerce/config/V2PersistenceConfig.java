package com.dunghaiquyen.ecommerce.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Persistence boundary for the V2 baseline (migration V1-V3). */
@Configuration
@EntityScan(basePackages = {
    "com.dunghaiquyen.ecommerce.modules.address.entity",
    "com.dunghaiquyen.ecommerce.modules.audit.entity",
    "com.dunghaiquyen.ecommerce.modules.auth.entity",
    "com.dunghaiquyen.ecommerce.modules.brand.entity",
    "com.dunghaiquyen.ecommerce.modules.cart.entity",
    "com.dunghaiquyen.ecommerce.modules.catalog.entity",
    "com.dunghaiquyen.ecommerce.modules.category.entity",
    "com.dunghaiquyen.ecommerce.modules.interaction.entity",
    "com.dunghaiquyen.ecommerce.modules.inventory.entity",
    "com.dunghaiquyen.ecommerce.modules.order.entity",
    "com.dunghaiquyen.ecommerce.modules.payment.entity",
    "com.dunghaiquyen.ecommerce.modules.product.entity",
    "com.dunghaiquyen.ecommerce.modules.returns.entity",
    "com.dunghaiquyen.ecommerce.modules.setting.entity",
    "com.dunghaiquyen.ecommerce.modules.user.entity"
})
@EnableJpaRepositories(basePackages = {
    "com.dunghaiquyen.ecommerce.modules.address.repository",
    "com.dunghaiquyen.ecommerce.modules.audit.repository",
    "com.dunghaiquyen.ecommerce.modules.auth.repository",
    "com.dunghaiquyen.ecommerce.modules.brand.repository",
    "com.dunghaiquyen.ecommerce.modules.cart.repository",
    "com.dunghaiquyen.ecommerce.modules.catalog.repository",
    "com.dunghaiquyen.ecommerce.modules.category.repository",
    "com.dunghaiquyen.ecommerce.modules.interaction.repository",
    "com.dunghaiquyen.ecommerce.modules.inventory.repository",
    "com.dunghaiquyen.ecommerce.modules.order.repository",
    "com.dunghaiquyen.ecommerce.modules.payment.repository",
    "com.dunghaiquyen.ecommerce.modules.product.repository",
    "com.dunghaiquyen.ecommerce.modules.report.repository",
    "com.dunghaiquyen.ecommerce.modules.returns.repository",
    "com.dunghaiquyen.ecommerce.modules.setting.repository",
    "com.dunghaiquyen.ecommerce.modules.user.repository"
})
public class V2PersistenceConfig {
}
