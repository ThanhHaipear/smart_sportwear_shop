package com.dunghaiquyen.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CatalogBoundaryTest {
    @Test void serviceDoesNotImportLegacyCoreAndMigrationHasNoPublicForeignKey() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String source;
        try (var files = Files.walk(root.resolve("src/main/java"))) {
            source = files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> { try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); } })
                    .reduce("", String::concat);
        }
        String migration = Files.readString(root.resolve("src/main/resources/db/migration/V1__create_catalog_schema.sql"));
        assertThat(source).doesNotContain("com.dunghaiquyen.ecommerce");
        assertThat(migration.toLowerCase()).doesNotContain("references public.");
        assertThat(migration.toLowerCase()).contains("revoke all on schema public from catalog_app");
    }
}
