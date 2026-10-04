package com.dunghaiquyen.ecommerce.infra.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class SeedDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final SeedDataService seedDataService;
    private final FashionProductSeeder fashionProductSeeder;
    private final RealRunningCatalogSeeder realRunningCatalogSeeder;

    public SeedDataRunner(
            SeedDataService seedDataService,
            FashionProductSeeder fashionProductSeeder,
            RealRunningCatalogSeeder realRunningCatalogSeeder) {
        this.seedDataService = seedDataService;
        this.fashionProductSeeder = fashionProductSeeder;
        this.realRunningCatalogSeeder = realRunningCatalogSeeder;
    }

    @Override
    public void run(String... args) {
        // Base sports catalog runs first because it resets the demo catalog.
        fashionProductSeeder.seed();
        realRunningCatalogSeeder.seed();

        SeedDataService.SeedSummary summary = seedDataService.seed();
        log.info(
                "Core seed complete: users={}, products={}, variants={}, orders={}",
                summary.users(),
                summary.products(),
                summary.variants(),
                summary.orders());

        log.info(
                "Demo credentials: {} / {}",
                SeedDataService.DEMO_ADMIN_EMAIL,
                "see APP_SEED_DEMO_PASSWORD or app.seed.demo-password");
    }
}
