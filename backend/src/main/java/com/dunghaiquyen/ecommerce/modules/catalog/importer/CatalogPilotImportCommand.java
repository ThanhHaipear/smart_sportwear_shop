package com.dunghaiquyen.ecommerce.modules.catalog.importer;

import com.dunghaiquyen.ecommerce.EcommerceBackendApplication;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Explicit one-shot command; normal application startup never imports demo catalog data. */
public final class CatalogPilotImportCommand {
    private CatalogPilotImportCommand() {}

    public static void main(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero or one argument: path to pilot manifest.json");
        }
        Path manifestPath = Path.of(args.length == 1 ? args[0] : "../catalog/pilot-v1/manifest.json")
                .toAbsolutePath()
                .normalize();
        SpringApplication application = new SpringApplication(EcommerceBackendApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            CatalogPilotImportService.ImportSummary summary = context
                    .getBean(CatalogPilotImportService.class)
                    .importManifest(manifestPath);
            System.out.printf(
                    "Catalog pilot import complete: dataset=%s products=%d variants=%d images=%d skipped=%s%n",
                    summary.datasetId(), summary.products(), summary.variants(), summary.images(), summary.skipped());
        }
    }
}
