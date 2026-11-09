package com.dunghaiquyen.ecommerce.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
