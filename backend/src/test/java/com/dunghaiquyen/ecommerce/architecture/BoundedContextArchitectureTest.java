package com.dunghaiquyen.ecommerce.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class BoundedContextArchitectureTest {

    private static final Path MODULE_SOURCES = locateModuleSources();
    private static final Pattern PERSISTENCE_IMPORT = Pattern.compile(
            "^import com\\.dunghaiquyen\\.ecommerce\\.modules\\.([^.]+)\\.(entity|repository)(?:\\..+)?;$");
    private static final Set<String> IDENTITY_MODULES = Set.of("address", "auth", "user");
    private static final Set<String> CATALOG_MODULES = Set.of(
            "banner", "brand", "catalog", "category", "collection", "combo", "inventory",
            "page", "product", "promotion", "review", "setting", "wishlist");
    private static final Set<String> SALES_MODULES = Set.of(
            "audit", "cart", "checkout", "notification", "order", "payment", "report", "returns", "shipping");
    private static final Set<String> RECOMMENDATION_MODULES = Set.of("interaction", "recommendation");
    private static final int APPROVED_ENTITY_EXCEPTION_COUNT = 84;
    private static final String APPROVED_ENTITY_EXCEPTION_SHA256 =
            "76ddb12967a3bb0e05f8fc3a3f52c70223505c333aaf1c84c4496ca414964d2a";

    @Test
    void identityDoesNotImportPersistenceFromOtherBoundedContexts() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String sourceModule : IDENTITY_MODULES) {
            collectPersistenceImportsOutside(
                    MODULE_SOURCES.resolve(sourceModule), IDENTITY_MODULES, violations);
        }

        assertThat(violations)
                .as("Identity must expose APIs instead of importing another context's entity/repository")
                .isEmpty();
    }

    @Test
    void checkoutUsesIdentityApiInsteadOfIdentityPersistence() throws IOException {
        List<String> violations = new ArrayList<>();
        collectPersistenceImportsFrom(
                MODULE_SOURCES.resolve("checkout"), IDENTITY_MODULES, violations);

        assertThat(violations)
                .as("Checkout must use Identity API contracts, not Identity entity/repository types")
                .isEmpty();
    }

    @Test
    void orderUsesAddressApiInsteadOfAddressPersistence() throws IOException {
        List<String> violations = new ArrayList<>();
        collectPersistenceImportsFrom(
                MODULE_SOURCES.resolve("order"), Set.of("address"), violations);

        assertThat(violations)
                .as("Order must use address.api contracts, not Address entity/repository types")
                .isEmpty();
    }

    @Test
    void shippingUsesAddressApiInsteadOfAddressPersistence() throws IOException {
        List<String> violations = new ArrayList<>();
        collectPersistenceImportsFrom(
                MODULE_SOURCES.resolve("shipping"), Set.of("address"), violations);

        assertThat(violations)
                .as("Shipping must use address.api contracts, not Address entity/repository types")
                .isEmpty();
    }

    @Test
    void boundedContextsDoNotImportRepositoriesAcrossBoundaries() throws IOException {
        List<String> violations = collectCrossContextImports("repository");

        assertThat(violations)
                .as("Bounded contexts must communicate through APIs, never another context's repository")
                .isEmpty();
    }

    @Test
    void crossContextEntityExceptionsMatchApprovedSnapshot() throws IOException, NoSuchAlgorithmException {
        List<String> exceptions = collectCrossContextImports("entity");
        String canonical = String.join("\n", exceptions);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(exceptions)
                .as("Approved cross-context entity/JPA exceptions changed; review ownership before updating snapshot")
                .hasSize(APPROVED_ENTITY_EXCEPTION_COUNT);
        assertThat(sha256)
                .as("Approved entity exception snapshot changed. Current exceptions: %s", exceptions)
                .isEqualTo(APPROVED_ENTITY_EXCEPTION_SHA256);
    }

    private void collectPersistenceImportsOutside(
            Path sourceRoot, Set<String> allowedModules, List<String> violations) throws IOException {
        inspectJavaSources(sourceRoot, (file, importedModule, importedLayer, importLine) -> {
            if (!allowedModules.contains(importedModule)) {
                violations.add(formatViolation(file, importedModule, importedLayer, importLine));
            }
        });
    }

    private void collectPersistenceImportsFrom(
            Path sourceRoot, Set<String> forbiddenModules, List<String> violations) throws IOException {
        inspectJavaSources(sourceRoot, (file, importedModule, importedLayer, importLine) -> {
            if (forbiddenModules.contains(importedModule)) {
                violations.add(formatViolation(file, importedModule, importedLayer, importLine));
            }
        });
    }

    private void inspectJavaSources(Path sourceRoot, PersistenceImportConsumer consumer) throws IOException {
        assertThat(sourceRoot).as("Module source directory").isDirectory();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher matcher = PERSISTENCE_IMPORT.matcher(line.trim());
                    if (matcher.matches()) {
                        consumer.accept(file, matcher.group(1), matcher.group(2), line.trim());
                    }
                }
            }
        }
    }

    private List<String> collectCrossContextImports(String layer) throws IOException {
        List<String> dependencies = new ArrayList<>();
        try (Stream<Path> moduleDirectories = Files.list(MODULE_SOURCES)) {
            for (Path sourceRoot : moduleDirectories.filter(Files::isDirectory).toList()) {
                String sourceModule = sourceRoot.getFileName().toString();
                String sourceContext = contextOf(sourceModule);
                inspectJavaSources(sourceRoot, (file, importedModule, importedLayer, importLine) -> {
                    if (importedLayer.equals(layer)
                            && !sourceContext.equals(contextOf(importedModule))) {
                        String relativeFile = MODULE_SOURCES.relativize(file).toString().replace('\\', '/');
                        dependencies.add(relativeFile + "|" + importLine);
                    }
                });
            }
        }
        return dependencies.stream().sorted().toList();
    }

    private String contextOf(String module) {
        if (IDENTITY_MODULES.contains(module)) return "identity";
        if (CATALOG_MODULES.contains(module)) return "catalog";
        if (SALES_MODULES.contains(module)) return "sales";
        if (RECOMMENDATION_MODULES.contains(module)) return "recommendation";
        return "unclassified:" + module;
    }

    private String formatViolation(Path file, String importedModule, String importedLayer, String importLine) {
        return file + " imports " + importedModule + "." + importedLayer + " via " + importLine;
    }

    private static Path locateModuleSources() {
        Path relativeSources = Path.of(
                "src", "main", "java", "com", "dunghaiquyen", "ecommerce", "modules");
        Path backendSources = Path.of("backend").resolve(relativeSources);
        if (Files.isDirectory(relativeSources)) {
            return relativeSources;
        }
        if (Files.isDirectory(backendSources)) {
            return backendSources;
        }
        throw new IllegalStateException("Cannot locate backend module sources from " + Path.of("").toAbsolutePath());
    }

    @FunctionalInterface
    private interface PersistenceImportConsumer {
        void accept(Path file, String importedModule, String importedLayer, String importLine);
    }
}
