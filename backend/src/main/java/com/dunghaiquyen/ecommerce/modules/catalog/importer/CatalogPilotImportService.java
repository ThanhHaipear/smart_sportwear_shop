package com.dunghaiquyen.ecommerce.modules.catalog.importer;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.brand.repository.BrandRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDataset;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.CatalogDatasetStatus;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.CatalogDatasetItemRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.repository.CatalogDatasetRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.service.CatalogDatasetService;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.category.repository.CategoryRepository;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductDetailResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductImageUploadResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.VariantCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.ImageSourceKind;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductDataSource;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductType;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductImageService;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductService;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductVariantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resumable, explicit importer for the versioned 30-product pilot catalog.
 * It deliberately uses normal catalog services so publish constraints, outbox
 * events, image provenance and variant validation are identical to admin flows.
 */
@Service
public class CatalogPilotImportService {
    private static final Logger log = LoggerFactory.getLogger(CatalogPilotImportService.class);
    private static final int EXPECTED_PRODUCTS = 30;
    private static final int EXPECTED_IMAGES = 60;
    private static final Map<String, Integer> EXPECTED_TYPES = Map.of(
            "SPORTS_TOP", 8,
            "SPORTS_BOTTOM", 6,
            "SPORTS_SHOES", 6,
            "SPORTS_UNIFORM", 6,
            "SPORTS_ACCESSORY", 4);
    private static final Map<String, Integer> EXPECTED_BRANDS = Map.of(
            "nike", 15,
            "adidas", 15);

    private final ObjectMapper objectMapper;
    private final ProductService productService;
    private final ProductVariantService variantService;
    private final ProductImageService imageService;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final CatalogDatasetRepository datasets;
    private final CatalogDatasetItemRepository datasetItems;
    private final CatalogDatasetService datasetService;

    public CatalogPilotImportService(
            ObjectMapper objectMapper,
            ProductService productService,
            ProductVariantService variantService,
            ProductImageService imageService,
            ProductRepository products,
            ProductVariantRepository variants,
            ProductImageRepository images,
            CategoryRepository categories,
            BrandRepository brands,
            CatalogDatasetRepository datasets,
            CatalogDatasetItemRepository datasetItems,
            CatalogDatasetService datasetService) {
        this.objectMapper = objectMapper;
        this.productService = productService;
        this.variantService = variantService;
        this.imageService = imageService;
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.categories = categories;
        this.brands = brands;
        this.datasets = datasets;
        this.datasetItems = datasetItems;
        this.datasetService = datasetService;
    }

    public ImportSummary importManifest(Path manifestPath) {
        PilotManifest manifest = readManifest(manifestPath);
        validateManifest(manifest, manifestPath);
        byte[] manifestBytes = readBytes(manifestPath);
        String manifestChecksum = sha256(manifestBytes);

        CatalogDataset dataset = datasets.findByNameAndVersion(
                        manifest.dataset().name(), manifest.dataset().version())
                .orElseGet(() -> createDataset(manifest, manifestPath, manifestChecksum));

        int variantCount = manifest.products().stream().mapToInt(p -> p.sizes().size()).sum();
        if (dataset.getStatus() == CatalogDatasetStatus.FROZEN) {
            long itemCount = datasetItems.countByDatasetId(dataset.getId());
            if (itemCount != EXPECTED_PRODUCTS || !dataset.getManifestChecksum().equals(manifestChecksum)) {
                throw new BusinessRuleException("Frozen pilot dataset does not match the local manifest");
            }
            List<Map<String, Object>> reportProducts = manifest.products().stream()
                    .map(spec -> reportProduct(
                            products.findBySlug(spec.slug())
                                    .orElseThrow(() -> new BusinessRuleException(
                                            "Frozen pilot product is missing: " + spec.slug())),
                            spec))
                    .toList();
            writeReport(manifestPath.getParent().resolve("import-result.json"), dataset.getId(), reportProducts);
            return new ImportSummary(dataset.getId(), EXPECTED_PRODUCTS, variantCount, EXPECTED_IMAGES, true);
        }

        Path catalogRoot = manifestPath.getParent();
        List<Map<String, Object>> reportProducts = new ArrayList<>();
        int completed = 0;
        for (PilotProduct spec : manifest.products()) {
            Product product = importProduct(manifest, spec, catalogRoot);
            if (!datasetItems.existsByDatasetIdAndProductId(dataset.getId(), product.getId())) {
                datasetService.addItem(
                        dataset.getId(),
                        product.getId(),
                        spec.code(),
                        "catalog://" + manifest.dataset().name() + "/" + manifest.dataset().version() + "/" + spec.code(),
                        sha256(writeBytes(spec)));
            }
            reportProducts.add(reportProduct(product, spec));
            completed++;
            log.info("Pilot catalog progress: {}/{} ({})", completed, EXPECTED_PRODUCTS, spec.code());
        }

        dataset = datasets.findById(dataset.getId()).orElseThrow();
        if (dataset.getStatus() == CatalogDatasetStatus.DRAFT) {
            datasetService.validate(dataset.getId());
        }
        datasetService.freeze(dataset.getId());
        writeReport(manifestPath.getParent().resolve("import-result.json"), dataset.getId(), reportProducts);
        return new ImportSummary(dataset.getId(), EXPECTED_PRODUCTS, variantCount, EXPECTED_IMAGES, false);
    }

    private Product importProduct(PilotManifest manifest, PilotProduct spec, Path catalogRoot) {
        Category category = categories.findBySlug(spec.categorySlug())
                .orElseThrow(() -> new BusinessRuleException("Unknown category: " + spec.categorySlug()));
        Brand brand = brands.findBySlug(spec.brandSlug())
                .orElseThrow(() -> new BusinessRuleException("Unknown brand: " + spec.brandSlug()));
        Map<String, String> attributes = new LinkedHashMap<>(spec.attributes());
        attributes.put("pilot_code", spec.code());
        attributes.put("catalog_dataset", manifest.dataset().name());
        attributes.put("catalog_dataset_version", manifest.dataset().version());

        Product product = products.findBySlug(spec.slug()).orElse(null);
        if (product == null) {
            ProductDetailResponse created = productService.create(new ProductCreateRequest(
                    spec.name(), spec.slug(), spec.shortDescription(), spec.description(),
                    category.getId(), brand.getId(), Gender.valueOf(spec.gender()), spec.sportCode(),
                    ProductType.valueOf(spec.productType()), ProductStatus.DRAFT, false, attributes,
                    ProductDataSource.DEMO));
            product = products.findById(created.id()).orElseThrow();
        } else {
            if (product.getDataSource() != ProductDataSource.DEMO
                    || !spec.code().equals(product.getAttributes().get("pilot_code"))) {
                throw new BusinessRuleException("Slug already belongs to non-pilot product: " + spec.slug());
            }
            boolean brandChanged = !brand.getId().equals(product.getBrand().getId());
            boolean attributesChanged = !attributes.equals(product.getAttributes());
            if (brandChanged || attributesChanged) {
                productService.update(product.getId(), new ProductUpdateRequest(
                        null, null, null, null, null, brandChanged ? brand.getId() : null,
                        null, null, null, null, null, attributesChanged ? attributes : null));
                product = products.findById(product.getId()).orElseThrow();
            }
        }

        for (String size : spec.sizes()) {
            String sku = (spec.code() + "-" + spec.colorCode() + "-" + size).toUpperCase();
            if (!variants.existsBySku(sku)) {
                variantService.create(product.getId(), new VariantCreateRequest(
                        sku, size, spec.colorCode(), BigDecimal.valueOf(spec.price()),
                        BigDecimal.valueOf(spec.compareAtPrice()), spec.stockPerVariant(), VariantStatus.ACTIVE));
            }
        }

        List<ProductImage> existingImages = images.findAllByProductIdOrderBySortOrderAsc(product.getId());
        for (PilotImage imageSpec : spec.images()) {
            String sourceGroup = spec.code() + "-" + (imageSpec.sortOrder() + 1);
            ProductImage existing = existingImages.stream()
                    .filter(image -> sourceGroup.equals(image.getSourceGroupCode()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                if (!imageSpec.sha256().equals(existing.getChecksum())) {
                    throw new BusinessRuleException("Existing image checksum mismatch: " + sourceGroup);
                }
                continue;
            }

            Path imagePath = catalogRoot.resolve(imageSpec.path()).normalize();
            ProductImageUploadResponse uploaded;
            try {
                uploaded = imageService.uploadImage(
                        product.getId(), new PathMultipartFile(imagePath), imageSpec.altText(), spec.colorCode(),
                        imageSpec.primary(), imageSpec.sortOrder());
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read pilot image " + imagePath, ex);
            }
            ProductImage image = images.findById(uploaded.id()).orElseThrow();
            image.setSourceKind(ImageSourceKind.valueOf(manifest.imagePolicy().sourceKind()));
            image.setSourceUri("catalog://" + manifest.dataset().name() + "/" + manifest.dataset().version()
                    + "/" + imageSpec.path());
            image.setLicenseCode(manifest.dataset().licenseCode());
            image.setSourceGroupCode(sourceGroup);
            images.saveAndFlush(image);
        }

        product = products.findById(product.getId()).orElseThrow();
        if (product.getStatus() != ProductStatus.ACTIVE) {
            productService.update(product.getId(), new ProductUpdateRequest(
                    null, null, null, null, null, null, null, null, null,
                    ProductStatus.ACTIVE, null, null));
        }
        return products.findById(product.getId()).orElseThrow();
    }

    private CatalogDataset createDataset(
            PilotManifest manifest, Path manifestPath, String manifestChecksum) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setName(manifest.dataset().name());
        dataset.setVersion(manifest.dataset().version());
        dataset.setManifestUri("repo://" + manifestPath.getFileName());
        dataset.setManifestChecksum(manifestChecksum);
        dataset.setSourceDescription(manifest.dataset().sourceDescription());
        dataset.setLicenseCode(manifest.dataset().licenseCode());
        dataset.setStatus(CatalogDatasetStatus.DRAFT);
        return datasets.save(dataset);
    }

    private PilotManifest readManifest(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), PilotManifest.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read pilot manifest: " + path, ex);
        }
    }

    private void validateManifest(PilotManifest manifest, Path manifestPath) {
        if (manifest.schemaVersion() != 1 || manifest.products().size() != EXPECTED_PRODUCTS) {
            throw new BusinessRuleException("Pilot manifest must use schema 1 and contain exactly 30 products");
        }
        if (!"DEMO".equals(manifest.dataset().dataSource())) {
            throw new BusinessRuleException("Pilot products must use dataSource=DEMO");
        }
        Map<String, Integer> actualTypes = new HashMap<>();
        Map<String, Integer> actualBrands = new HashMap<>();
        Set<String> codes = new HashSet<>();
        Set<String> slugs = new HashSet<>();
        int imageCount = 0;
        for (PilotProduct product : manifest.products()) {
            actualTypes.merge(product.productType(), 1, Integer::sum);
            actualBrands.merge(product.brandSlug(), 1, Integer::sum);
            if (!codes.add(product.code()) || !slugs.add(product.slug())) {
                throw new BusinessRuleException("Duplicate pilot product code or slug");
            }
            if (product.sizes().isEmpty() || product.stockPerVariant() <= 0 || product.price() <= 0
                    || product.compareAtPrice() < product.price()) {
                throw new BusinessRuleException("Invalid variant rule for " + product.code());
            }
            if (product.images().size() < 2 || product.images().size() > 4
                    || product.images().stream().filter(PilotImage::primary).count() != 1) {
                throw new BusinessRuleException("Each product needs 2-4 images and exactly one primary: " + product.code());
            }
            imageCount += product.images().size();
            for (PilotImage image : product.images()) {
                validateImage(manifest.imagePolicy(), manifestPath.getParent().resolve(image.path()), image.sha256());
            }
        }
        if (!EXPECTED_TYPES.equals(actualTypes) || !EXPECTED_BRANDS.equals(actualBrands)
                || imageCount != EXPECTED_IMAGES) {
            throw new BusinessRuleException(
                    "Pilot category, brand allocation or image count does not match the approved rule");
        }
        Set<String> genders = manifest.products().stream().map(PilotProduct::gender).collect(java.util.stream.Collectors.toSet());
        Set<String> sports = manifest.products().stream().map(PilotProduct::sportCode).collect(java.util.stream.Collectors.toSet());
        if (!genders.containsAll(Set.of("MEN", "WOMEN", "UNISEX"))
                || !sports.containsAll(Set.of("FOOTBALL", "RUNNING"))) {
            throw new BusinessRuleException("Pilot must cover MEN/WOMEN/UNISEX and FOOTBALL/RUNNING");
        }
    }

    private void validateImage(ImagePolicy policy, Path path, String expectedChecksum) {
        try {
            long bytes = Files.size(path);
            if (bytes < policy.minBytes() || bytes > policy.maxBytes() || !sha256(Files.readAllBytes(path)).equals(expectedChecksum)) {
                throw new BusinessRuleException("Image bytes/checksum invalid: " + path.getFileName());
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null || image.getWidth() != policy.requiredWidth() || image.getHeight() != policy.requiredHeight()) {
                throw new BusinessRuleException("Image dimensions invalid: " + path.getFileName());
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot validate pilot image: " + path, ex);
        }
    }

    private Map<String, Object> reportProduct(Product product, PilotProduct spec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", product.getId());
        row.put("slug", product.getSlug());
        row.put("status", product.getStatus());
        row.put("dataSource", product.getDataSource());
        Brand brand = brands.findBySlug(spec.brandSlug())
                .orElseThrow(() -> new BusinessRuleException("Unknown brand: " + spec.brandSlug()));
        if (!brand.getId().equals(product.getBrand().getId())) {
            throw new BusinessRuleException("Pilot product brand no longer matches manifest: " + product.getSlug());
        }
        row.put("brand", Map.of(
                "id", brand.getId(),
                "code", brand.getCode(),
                "name", brand.getName(),
                "slug", brand.getSlug()));
        row.put("images", images.findAllByProductIdOrderBySortOrderAsc(product.getId()).stream()
                .map(this::reportImage)
                .toList());
        return row;
    }

    private Map<String, Object> reportImage(ProductImage image) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", image.getId());
        row.put("publicId", image.getPublicId());
        row.put("assetVersion", image.getAssetVersion());
        row.put("secureUrl", image.getSecureUrl());
        row.put("checksum", image.getChecksum());
        row.put("width", image.getWidth());
        row.put("height", image.getHeight());
        row.put("format", image.getFormat());
        row.put("bytes", image.getBytes());
        row.put("primary", image.isPrimary());
        row.put("sourceKind", image.getSourceKind());
        row.put("licenseCode", image.getLicenseCode());
        row.put("sourceUri", image.getSourceUri());
        row.put("sourceGroupCode", image.getSourceGroupCode());
        return row;
    }

    private void writeReport(Path path, UUID datasetId, List<Map<String, Object>> productsReport) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("datasetId", datasetId);
        report.put("importedAt", Instant.now().toString());
        report.put("products", productsReport);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        } catch (IOException ex) {
            throw new IllegalStateException("Catalog imported but import report could not be written: " + path, ex);
        }
    }

    private byte[] readBytes(Path path) {
        try { return Files.readAllBytes(path); }
        catch (IOException ex) { throw new IllegalArgumentException("Cannot read " + path, ex); }
    }

    private byte[] writeBytes(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        catch (IOException ex) { throw new IllegalStateException("Cannot serialize checksum input", ex); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    public record ImportSummary(UUID datasetId, int products, int variants, int images, boolean skipped) {}
    public record PilotManifest(int schemaVersion, DatasetSpec dataset, ImagePolicy imagePolicy, List<PilotProduct> products) {}
    public record DatasetSpec(String name, String version, String dataSource, String licenseCode, String sourceDescription) {}
    public record ImagePolicy(
            String sourceKind, String generator, String originalFormat, int requiredWidth, int requiredHeight,
            long minBytes, long maxBytes, String colorSpace, String embeddingSource, String uiDelivery) {}
    public record PilotProduct(
            String code, String name, String slug, String categorySlug, String brandSlug, String productType,
            String gender, String sportCode, String colorCode, List<String> sizes, long price, long compareAtPrice,
            int stockPerVariant, String shortDescription, String description, Map<String, String> attributes,
            List<PilotImage> images) {}
    public record PilotImage(
            String path, String sha256, String altText, String view, int sortOrder, boolean primary) {}
}
