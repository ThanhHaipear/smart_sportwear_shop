package com.dunghaiquyen.ecommerce.modules.product.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.response.PageMeta;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.brand.repository.BrandRepository;
import com.dunghaiquyen.ecommerce.modules.catalog.service.CatalogReferenceService;
import com.dunghaiquyen.ecommerce.modules.catalog.entity.ProductTypeDefinition;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.category.repository.CategoryRepository;
import com.dunghaiquyen.ecommerce.modules.product.dto.AdminProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.AdminProductListQuery;
import com.dunghaiquyen.ecommerce.modules.product.dto.CatalogRefResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductCreateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductDetailResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductListQuery;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductSuggestionResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductVariantResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ReviewSummaryResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductDataSource;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.mapper.ProductMapper;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.inventory.repository.InventoryTransactionRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderItemRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.spec.ProductSpecifications;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogEventType;
import com.dunghaiquyen.ecommerce.visualsearch.outbox.CatalogOutboxService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int SUGGESTION_LIMIT = 8;
    private static final int RELATED_PRODUCTS_LIMIT = 8;

    /** Phase N3 unified "sort" param values - kept alongside legacy sortBy/sortOrder, see ProductListQuery's javadoc. */
    private static final java.util.Set<String> VALID_SORT_VALUES =
            java.util.Set.of("newest", "price_asc", "price_desc", "bestselling");

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductMapper productMapper;
    private final CartItemRepository cartItemRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final OrderItemRepository orderItemRepository;
    private final CatalogOutboxService catalogOutboxService;
    private final CatalogReferenceService catalogReferenceService;

    public ProductService(
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            ProductImageRepository imageRepository,
            CategoryRepository categoryRepository,
            BrandRepository brandRepository,
            ProductMapper productMapper,
            CartItemRepository cartItemRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            OrderItemRepository orderItemRepository,
            CatalogOutboxService catalogOutboxService,
            CatalogReferenceService catalogReferenceService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productMapper = productMapper;
        this.cartItemRepository = cartItemRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.orderItemRepository = orderItemRepository;
        this.catalogOutboxService = catalogOutboxService;
        this.catalogReferenceService = catalogReferenceService;
    }

    public record ListResult(List<ProductListItemResponse> items, PageMeta meta) {
    }

    public record AdminListResult(List<AdminProductListItemResponse> items, PageMeta meta) {
    }

    /**
     * Admin listing - deliberately its own method/spec chain, not a thin
     * wrapper around listPublic: admin must see DRAFT/INACTIVE products too
     * (no hasStatus(ACTIVE) forced in), status here is an optional filter like
     * any other, and the response shape (AdminProductListItemResponse) adds
     * `featured` that the public list item shape has no reason to expose.
     */
    @Transactional(readOnly = true)
    public AdminListResult listAdmin(AdminProductListQuery q) {
        Specification<Product> spec = ProductSpecifications.fetchBrandAndCategory();
        if (q.status() != null) {
            spec = spec.and(ProductSpecifications.hasStatus(q.status()));
        }
        if (q.categoryId() != null) {
            spec = spec.and(ProductSpecifications.hasCategoryId(q.categoryId()));
        }
        if (q.brandId() != null) {
            spec = spec.and(ProductSpecifications.hasBrandId(q.brandId()));
        }
        if (q.featured() != null) {
            spec = spec.and(ProductSpecifications.isFeatured(q.featured()));
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            spec = spec.and(ProductSpecifications.keywordMatches(q.keyword().trim()));
        }
        if (q.productType() != null) {
            spec = spec.and(ProductSpecifications.hasProductType(q.productType()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(q.page()), resolveLimit(q.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Product> page = productRepository.findAll(spec, pageable);
        List<AdminProductListItemResponse> items = assembleAdminListItems(page.getContent());
        return new AdminListResult(items, PageMeta.from(page));
    }

    @Transactional(readOnly = true)
    public ListResult listPublic(ProductListQuery q) {
        validatePriceRange(q.minPrice(), q.maxPrice());

        Pageable emptyPageable = PageRequest.of(resolvePageIndex(q.page()), resolveLimit(q.limit()));
        Optional<UUID> resolvedCategoryId = resolveCategoryFilter(q);
        if (resolvedCategoryId.isEmpty() && hasCategoryFilter(q)) {
            return new ListResult(List.of(), PageMeta.from(Page.empty(emptyPageable)));
        }
        Optional<UUID> resolvedBrandId = resolveBrandFilter(q);
        if (resolvedBrandId.isEmpty() && hasBrandFilter(q)) {
            return new ListResult(List.of(), PageMeta.from(Page.empty(emptyPageable)));
        }

        Specification<Product> spec = ProductSpecifications.hasStatus(ProductStatus.ACTIVE)
                .and(ProductSpecifications.fetchBrandAndCategory());
        spec = applyCommonFilters(spec, q, resolvedCategoryId.orElse(null), resolvedBrandId.orElse(null));

        Pageable pageable;
        String unifiedSort = q.sort();
        if (unifiedSort != null && !unifiedSort.isBlank()) {
            String normalized = unifiedSort.trim().toLowerCase();
            if (!VALID_SORT_VALUES.contains(normalized)) {
                throw new BusinessRuleException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Invalid sort value: '" + unifiedSort + "' (expected one of " + VALID_SORT_VALUES + ")");
            }
            switch (normalized) {
                case "price_asc" -> {
                    spec = spec.and(ProductSpecifications.orderByMinPrice(true));
                    pageable = emptyPageable;
                }
                case "price_desc" -> {
                    spec = spec.and(ProductSpecifications.orderByMinPrice(false));
                    pageable = emptyPageable;
                }
                case "bestselling" -> {
                    spec = spec.and(ProductSpecifications.orderByBestSelling());
                    pageable = emptyPageable;
                }
                default -> pageable = PageRequest.of(
                        resolvePageIndex(q.page()), resolveLimit(q.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
            }
        } else if ("price".equalsIgnoreCase(q.sortBy())) {
            // Legacy path, unchanged from before Phase N3.
            boolean ascending = !"desc".equalsIgnoreCase(q.sortOrder());
            spec = spec.and(ProductSpecifications.orderByMinPrice(ascending));
            pageable = emptyPageable;
        } else {
            pageable = PageRequest.of(resolvePageIndex(q.page()), resolveLimit(q.limit()), resolveSort(q));
        }

        Page<Product> page = productRepository.findAll(spec, pageable);
        List<ProductListItemResponse> items = assembleListItems(page.getContent());
        return new ListResult(items, PageMeta.from(page));
    }

    /**
     * Phase N3 autocomplete: deliberately not paginated/scored - "thuc dung,
     * khong AI, khong fuzzy". A plain case-insensitive name match, capped at a
     * small fixed count, featured products first as the one simple relevance
     * signal already available on Product, then name for a stable order.
     */
    @Transactional(readOnly = true)
    public List<ProductSuggestionResponse> searchSuggestions(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        Pageable top = PageRequest.of(
                0, SUGGESTION_LIMIT, Sort.by(Sort.Direction.DESC, "featured").and(Sort.by(Sort.Direction.ASC, "name")));
        Specification<Product> spec = ProductSpecifications.hasStatus(ProductStatus.ACTIVE)
                .and(ProductSpecifications.keywordMatches(q.trim()));
        List<Product> products = productRepository.findAll(spec, top).getContent();
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductImage>> imagesByProduct = imageRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId()));
        return products.stream()
                .map(p -> new ProductSuggestionResponse(
                        p.getId(), p.getName(), p.getSlug(), pickThumbnail(imagesByProduct.getOrDefault(p.getId(), List.of()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getPublicDetail(String slugOrId) {
        Product product = findVisibleProduct(slugOrId);
        return assembleDetail(product, true);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getAdminDetail(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        return assembleDetail(product, false);
    }

    @Transactional
    public ProductDetailResponse create(ProductCreateRequest request) {
        if (request.status() == ProductStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "Create product as DRAFT first; add a sellable variant and a valid primary image before activation");
        }
        if (productRepository.existsBySlug(request.slug())) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        Brand brand = brandRepository.findById(request.brandId())
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));

        Product product = new Product();
        ProductTypeDefinition productType = catalogReferenceService.requireProductType(request.productType());
        product.setCategory(category);
        product.setBrand(brand);
        product.setProductTypeDefinition(productType);
        product.setSizeSystem(productType.getDefaultSizeSystem());
        product.setName(request.name().trim());
        product.setSlug(request.slug());
        product.setShortDescription(request.shortDescription());
        product.setDescription(request.description());
        product.setGender(request.gender() != null ? request.gender() : Gender.UNISEX);
        if (request.sportType() != null && !request.sportType().isBlank()) {
            product.getSports().add(catalogReferenceService.requireSport(request.sportType()));
        }
        product.setStatus(request.status() != null ? request.status() : ProductStatus.DRAFT);
        product.setDataSource(request.dataSource() != null ? request.dataSource() : ProductDataSource.REAL);
        product.setFeatured(request.isFeatured() != null && request.isFeatured());
        if (request.attributes() != null) {
            product.setAttributes(request.attributes());
        }

        try {
            product = productRepository.save(product);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        if (product.getStatus() == ProductStatus.ACTIVE) {
            catalogOutboxService.append(CatalogEventType.PRODUCT_ACTIVATED, product.getId(), null);
        }
        return assembleDetail(product, false);
    }

    @Transactional
    public ProductDetailResponse update(UUID id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        ProductStatus previousStatus = product.getStatus();

        if (request.slug() != null && productRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }

        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
            product.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepository.findById(request.brandId())
                    .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
            product.setBrand(brand);
        }
        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.slug() != null) {
            product.setSlug(request.slug());
        }
        if (request.shortDescription() != null) {
            product.setShortDescription(request.shortDescription());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.gender() != null) {
            product.setGender(request.gender());
        }
        if (request.sportType() != null) {
            product.getSports().clear();
            if (!request.sportType().isBlank()) {
                product.getSports().add(catalogReferenceService.requireSport(request.sportType()));
            }
        }
        if (request.productType() != null) {
            ProductTypeDefinition type = catalogReferenceService.requireProductType(request.productType());
            product.setProductTypeDefinition(type);
            product.setSizeSystem(type.getDefaultSizeSystem());
        }
        if (request.status() != null) {
            product.setStatus(request.status());
        }
        if (request.isFeatured() != null) {
            product.setFeatured(request.isFeatured());
        }
        if (request.attributes() != null) {
            product.setAttributes(request.attributes());
        }

        try {
            product = productRepository.save(product);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        if (previousStatus != product.getStatus()) {
            if (product.getStatus() == ProductStatus.ACTIVE) {
                catalogOutboxService.append(CatalogEventType.PRODUCT_ACTIVATED, product.getId(), null);
            } else if (previousStatus == ProductStatus.ACTIVE) {
                catalogOutboxService.append(CatalogEventType.PRODUCT_DEACTIVATED, product.getId(), null);
            }
        } else if (product.getStatus() == ProductStatus.ACTIVE) {
            catalogOutboxService.append(CatalogEventType.PRODUCT_REINDEX_REQUESTED, product.getId(), null);
        }
        return assembleDetail(product, false);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        List<UUID> variantIds = product.getVariants().stream()
                .map(ProductVariant::getId)
                .toList();

        if (!variantIds.isEmpty() && orderItemRepository.existsByVariantIdIn(variantIds)) {
            throw new BusinessRuleException(
                    "Không thể xóa sản phẩm đã có lịch sử đơn hàng. Hãy chuyển trạng thái sang INACTIVE thay thế.");
        }

        if (!variantIds.isEmpty()) {
            cartItemRepository.deleteAllByVariantIdIn(variantIds);
            inventoryTransactionRepository.deleteAllByVariantIdIn(variantIds);
        }

        productRepository.delete(product);
    }

    /**
     * Slug format (Patterns.SLUG: lowercase letters/digits/hyphens) happens to
     * also accept UUID-shaped strings, so "looks like a UUID" does not mean
     * "is an id" - a product could legitimately have a slug like
     * "123e4567-e89b-12d3-a456-426614174000". Parsing as UUID is only tried
     * first as an optimization (id lookup is a direct PK hit); if it parses
     * but no ACTIVE product has that id, fall back to a slug lookup with the
     * same string instead of 404-ing immediately.
     */
    private Product findVisibleProduct(String slugOrId) {
        UUID id = tryParseUuid(slugOrId);
        if (id != null) {
            Optional<Product> byId = productRepository.findById(id)
                    .filter(p -> p.getStatus() == ProductStatus.ACTIVE);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        return productRepository.findBySlugAndStatus(slugOrId, ProductStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** True if the caller supplied EITHER form of the category filter (id or slug). */
    private boolean hasCategoryFilter(ProductListQuery q) {
        return q.categoryId() != null || (q.categorySlug() != null && !q.categorySlug().isBlank());
    }

    private boolean hasBrandFilter(ProductListQuery q) {
        return q.brandId() != null || (q.brandSlug() != null && !q.brandSlug().isBlank());
    }

    /**
     * categoryId wins if both are given (an explicit id is unambiguous and
     * already validated by being a UUID; a slug still needs a lookup). Empty
     * Optional + hasCategoryFilter()==true means "a filter was given but
     * didn't resolve to anything" - callers must treat that as zero results,
     * not "no filter at all".
     */
    private Optional<UUID> resolveCategoryFilter(ProductListQuery q) {
        if (q.categoryId() != null) {
            return Optional.of(q.categoryId());
        }
        if (q.categorySlug() != null && !q.categorySlug().isBlank()) {
            return categoryRepository.findBySlug(q.categorySlug().trim()).map(Category::getId);
        }
        return Optional.empty();
    }

    private Optional<UUID> resolveBrandFilter(ProductListQuery q) {
        if (q.brandId() != null) {
            return Optional.of(q.brandId());
        }
        if (q.brandSlug() != null && !q.brandSlug().isBlank()) {
            return brandRepository.findBySlug(q.brandSlug().trim()).map(Brand::getId);
        }
        return Optional.empty();
    }

    private void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "minPrice must not be negative");
        }
        if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "maxPrice must not be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "minPrice must not be greater than maxPrice");
        }
    }

    private Specification<Product> applyCommonFilters(
            Specification<Product> spec, ProductListQuery q, UUID resolvedCategoryId, UUID resolvedBrandId) {
        if (resolvedCategoryId != null) {
            spec = spec.and(ProductSpecifications.hasCategoryIdOrParentId(resolvedCategoryId));
        }
        if (resolvedBrandId != null) {
            spec = spec.and(ProductSpecifications.hasBrandId(resolvedBrandId));
        }
        String effectiveKeyword = q.q() != null && !q.q().isBlank() ? q.q() : q.keyword();
        if (effectiveKeyword != null && !effectiveKeyword.isBlank()) {
            spec = spec.and(ProductSpecifications.keywordMatches(effectiveKeyword.trim()));
        }
        if (q.gender() != null) {
            spec = spec.and(ProductSpecifications.hasGender(q.gender()));
        }
        if (q.sportType() != null && !q.sportType().isBlank()) {
            spec = spec.and(ProductSpecifications.hasSportType(q.sportType().trim()));
        }
        if (q.surface() != null && !q.surface().isBlank()) {
            java.util.List<String> surfaceSlugs = switch (q.surface().trim().toUpperCase()) {
                case "FG" -> java.util.List.of("giay-da-bong-fg");
                case "TF", "AG" -> java.util.List.of("giay-da-bong-tf");
                case "IC", "FUTSAL" -> java.util.List.of("giay-futsal");
                default -> java.util.List.<String>of();
            };
            if (!surfaceSlugs.isEmpty()) {
                spec = spec.and(ProductSpecifications.hasCategorySlugIn(surfaceSlugs));
            }
        }
        if (q.size() != null && !q.size().isBlank()) {
            spec = spec.and(ProductSpecifications.hasVariantSize(q.size().trim()));
        }
        if (q.color() != null && !q.color().isBlank()) {
            spec = spec.and(ProductSpecifications.hasVariantColorFamily(q.color().trim()));
        }
        if (q.minPrice() != null && q.maxPrice() != null) {
            spec = spec.and(ProductSpecifications.hasVariantPriceBetween(q.minPrice(), q.maxPrice()));
        } else if (q.minPrice() != null) {
            spec = spec.and(ProductSpecifications.hasVariantPriceGte(q.minPrice()));
        } else if (q.maxPrice() != null) {
            spec = spec.and(ProductSpecifications.hasVariantPriceLte(q.maxPrice()));
        }
        if (q.discount() != null && !q.discount().isBlank()) {
            Specification<Product> discountSpec = ProductSpecifications.hasVariantDiscountBand(q.discount().trim());
            spec = spec.and(discountSpec);
        }
        if (q.productType() != null) {
            spec = spec.and(ProductSpecifications.hasProductType(q.productType()));
        }
        if (q.collection() != null && !q.collection().isBlank()) {
            // Collections are deferred; an explicit collection filter must not
            // silently return products from the unfiltered catalog.
            spec = spec.and((root, query, cb) -> cb.disjunction());
        }
        if (q.featured() != null) {
            spec = spec.and(ProductSpecifications.isFeatured(q.featured()));
        }
        return spec;
    }

    private int resolvePageIndex(Integer page) {
        return (page != null && page > 0) ? page - 1 : 0;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Sort resolveSort(ProductListQuery q) {
        boolean descRequested = "desc".equalsIgnoreCase(q.sortOrder());
        boolean ascRequested = "asc".equalsIgnoreCase(q.sortOrder());
        if ("name".equalsIgnoreCase(q.sortBy())) {
            return Sort.by(descRequested ? Sort.Direction.DESC : Sort.Direction.ASC, "name");
        }
        // default: newest first, unless caller explicitly asks for asc
        return Sort.by(ascRequested ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt");
    }

    /**
     * Public entry point used by CollectionService to build the product list for
     * a collection detail page. Fetches by IDs (preserving the caller's order),
     * applies an optional status filter, then assembles the same list-item shape
     * that {@link #listPublic} produces - so the product cards on a collection
     * page look identical to those on the catalog page.
     */
    @Transactional(readOnly = true)
    public List<ProductListItemResponse> assembleListItemsByIds(List<UUID> productIds, ProductStatus statusFilter) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        List<Product> products = productRepository.findAllById(productIds);
        if (statusFilter != null) {
            products = products.stream().filter(p -> p.getStatus() == statusFilter).toList();
        }
        // Re-apply the caller's sort order (JPA findAllById does not guarantee order).
        Map<UUID, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        products = productIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return assembleListItems(products);
    }

    /**
     * Re-enriches untrusted internal-search IDs from the commerce database,
     * reapplies hard filters, and preserves the internal rank order.
     */
    @Transactional(readOnly = true)
    public List<ProductListItemResponse> assembleRankedSearchItems(
            List<UUID> productIds, ProductListQuery query) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> products = productRepository
                .findActiveByIdInWithBrandAndCategory(productIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
        Map<UUID, List<ProductVariant>> variants = variantRepository.findAllByProductIdIn(productIds).stream()
                .filter(variant -> variant.getStatus() == VariantStatus.ACTIVE)
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));
        List<UUID> accepted = productIds.stream()
                .filter(products::containsKey)
                .filter(id -> matchesCommerceFilters(products.get(id), variants.getOrDefault(id, List.of()), query))
                .toList();
        return assembleListItemsByIds(accepted, ProductStatus.ACTIVE);
    }

    private boolean matchesCommerceFilters(Product product, List<ProductVariant> variants, ProductListQuery query) {
        if (query.gender() != null && product.getGender() != null
                && product.getGender() != query.gender() && product.getGender() != Gender.UNISEX) {
            return false;
        }
        if (query.productType() != null && product.getProductType() != query.productType()) {
            return false;
        }
        if (query.categoryId() != null && !product.getCategory().getId().equals(query.categoryId())) {
            return false;
        }
        if (query.brandId() != null && !product.getBrand().getId().equals(query.brandId())) {
            return false;
        }
        if (query.categorySlug() != null
                && !product.getCategory().getSlug().equalsIgnoreCase(query.categorySlug())) {
            return false;
        }
        if (query.brandSlug() != null && !product.getBrand().getSlug().equalsIgnoreCase(query.brandSlug())) {
            return false;
        }
        return variants.stream().anyMatch(variant -> {
            int available = Math.max(0, variant.getStockQuantity() - variant.getReservedQuantity());
            return available > 0
                    && (query.size() == null || variant.getSize().equalsIgnoreCase(query.size()))
                    && (query.color() == null || variant.getColor().toLowerCase()
                            .contains(query.color().toLowerCase()))
                    && (query.minPrice() == null || variant.getPrice().compareTo(query.minPrice()) >= 0)
                    && (query.maxPrice() == null || variant.getPrice().compareTo(query.maxPrice()) <= 0);
        });
    }

    private List<ProductListItemResponse> assembleListItems(List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> variantsByProduct = variantRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<UUID, List<ProductImage>> imagesByProduct = imageRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId()));
        return products.stream()
                .map(p -> toListItem(
                        p,
                        variantsByProduct.getOrDefault(p.getId(), List.of()),
                        imagesByProduct.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private ProductListItemResponse toListItem(
            Product product, List<ProductVariant> variants, List<ProductImage> images) {
        List<BigDecimal> visiblePrices = variants.stream()
                .filter(v -> v.getStatus() != VariantStatus.INACTIVE)
                .map(ProductVariant::getPrice)
                .toList();
        BigDecimal minPrice = visiblePrices.stream().min(Comparator.naturalOrder()).orElse(null);
        BigDecimal maxPrice = visiblePrices.stream().max(Comparator.naturalOrder()).orElse(null);
        int availableQuantity = variants.stream()
                .filter(v -> v.getStatus() == VariantStatus.ACTIVE)
                .mapToInt(v -> Math.max(0, v.getStockQuantity() - v.getReservedQuantity()))
                .sum();
        ProductVariant saleVariant = variants.stream()
                .filter(v -> v.getStatus() != VariantStatus.INACTIVE)
                .filter(v -> v.getCompareAtPrice() != null && v.getCompareAtPrice().compareTo(v.getPrice()) > 0)
                .min(Comparator.comparing(ProductVariant::getPrice))
                .orElse(null);
        BigDecimal salePrice = saleVariant != null ? saleVariant.getPrice() : null;
        BigDecimal compareAtPrice = saleVariant != null ? saleVariant.getCompareAtPrice() : null;
        Integer discountPercent = saleVariant != null
                ? compareAtPrice.subtract(salePrice)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(compareAtPrice, 0, java.math.RoundingMode.HALF_UP)
                        .intValue()
                : null;

        return new ProductListItemResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getShortDescription(),
                toRef(product.getBrand()),
                toRef(product.getCategory()),
                pickThumbnail(images),
                minPrice,
                maxPrice,
                salePrice,
                compareAtPrice,
                discountPercent,
                availableQuantity,
                product.getStatus(),
                product.getProductType());
    }

    private List<AdminProductListItemResponse> assembleAdminListItems(List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> variantsByProduct = variantRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<UUID, List<ProductImage>> imagesByProduct = imageRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId()));

        return products.stream()
                .map(p -> toAdminListItem(
                        p,
                        variantsByProduct.getOrDefault(p.getId(), List.of()),
                        imagesByProduct.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** Unlike the public toListItem, min/max price here spans ALL variants regardless of status - admin wants the full picture, not just what is currently purchasable. */
    private AdminProductListItemResponse toAdminListItem(Product product, List<ProductVariant> variants, List<ProductImage> images) {
        List<BigDecimal> prices = variants.stream().map(ProductVariant::getPrice).toList();
        BigDecimal minPrice = prices.stream().min(Comparator.naturalOrder()).orElse(null);
        BigDecimal maxPrice = prices.stream().max(Comparator.naturalOrder()).orElse(null);
        String representativeSku = variants.stream()
                .map(ProductVariant::getSku)
                .filter(Objects::nonNull)
                .min(String.CASE_INSENSITIVE_ORDER)
                .orElse(product.getSlug());
        int availableQuantity = variants.stream()
                .mapToInt(variant -> Math.max(0, variant.getStockQuantity() - variant.getReservedQuantity()))
                .sum();

        return new AdminProductListItemResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getStatus(),
                product.isFeatured(),
                toRef(product.getBrand()),
                toRef(product.getCategory()),
                pickThumbnail(images),
                minPrice,
                maxPrice,
                representativeSku,
                availableQuantity);
    }

    private ProductDetailResponse assembleDetail(Product product, boolean visibleOnly) {
        List<ProductVariant> variants = variantRepository.findAllByProductIdOrderByCreatedAtAsc(product.getId());
        List<ProductImage> images = imageRepository.findAllByProductIdOrderBySortOrderAsc(product.getId());

        if (visibleOnly) {
            variants = variants.stream().filter(v -> v.getStatus() != VariantStatus.INACTIVE).toList();
        }

        ReviewSummaryResponse reviewSummary = null;
        List<ProductListItemResponse> relatedProducts = visibleOnly ? findRelatedProducts(product) : List.of();

        List<ProductVariantResponse> variantResponses = variants.stream().map(productMapper::toVariantResponse).toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getShortDescription(),
                product.getDescription(),
                product.getGender(),
                product.getSportType(),
                product.getProductType(),
                toRef(product.getBrand()),
                toRef(product.getCategory()),
                images.stream().map(productMapper::toImageResponse).toList(),
                variantResponses,
                product.getStatus(),
                product.isFeatured(),
                product.getAttributes(),
                reviewSummary,
                relatedProducts);
    }

    /**
     * Same category OR same brand, ACTIVE only, never the product itself.
     * Deterministic order (newest, then id as a final tiebreaker) - no
     * personalization/recommendation engine, per Phase N4 scope. Product.featured
     * is transient, so it must not be passed to a database Sort.
     */
    private List<ProductListItemResponse> findRelatedProducts(Product product) {
        Specification<Product> spec = ProductSpecifications.hasStatus(ProductStatus.ACTIVE)
                .and(ProductSpecifications.hasIdNot(product.getId()))
                .and(ProductSpecifications.fetchBrandAndCategory())
                .and(ProductSpecifications.hasCategoryId(product.getCategory().getId())
                        .or(ProductSpecifications.hasBrandId(product.getBrand().getId())));
        Pageable pageable = PageRequest.of(
                0,
                RELATED_PRODUCTS_LIMIT,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id")));
        List<Product> related = productRepository.findAll(spec, pageable).getContent();
        return assembleListItems(related);
    }

    private String pickThumbnail(List<ProductImage> images) {
        return ThumbnailResolver.resolve(images);
    }

    private CatalogRefResponse toRef(Category category) {
        return category == null ? null : new CatalogRefResponse(category.getId(), category.getName());
    }

    private CatalogRefResponse toRef(Brand brand) {
        return brand == null ? null : new CatalogRefResponse(brand.getId(), brand.getName());
    }
}
