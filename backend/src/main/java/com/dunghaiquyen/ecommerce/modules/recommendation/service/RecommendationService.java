package com.dunghaiquyen.ecommerce.modules.recommendation.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.api.CartReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartOwner;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.product.dto.CatalogRefResponse;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.api.ProductReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.modules.recommendation.dto.CartRecommendationResponse;
import com.dunghaiquyen.ecommerce.modules.recommendation.dto.RecommendationItemResponse;
import com.dunghaiquyen.ecommerce.modules.recommendation.dto.RecommendationResponse;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.AssociationRule;
import com.dunghaiquyen.ecommerce.modules.recommendation.repository.AssociationRuleRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class RecommendationService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int CANDIDATE_MULTIPLIER = 4;

    private static final String FREQUENTLY_BOUGHT_TOGETHER = "FREQUENTLY_BOUGHT_TOGETHER";
    private static final String CART_RECOMMENDATION = "CART_RECOMMENDATION";

    private final AssociationRuleRepository associationRuleRepository;
    private final ProductReferenceQuery productReferenceQuery;
    private final CartReferenceQuery cartReferenceQuery;
    private final RecommendationCacheService recommendationCacheService;

    public RecommendationService(
            AssociationRuleRepository associationRuleRepository,
            ProductReferenceQuery productReferenceQuery,
            CartReferenceQuery cartReferenceQuery,
            RecommendationCacheService recommendationCacheService) {
        this.associationRuleRepository = associationRuleRepository;
        this.productReferenceQuery = productReferenceQuery;
        this.cartReferenceQuery = cartReferenceQuery;
        this.recommendationCacheService = recommendationCacheService;
    }

    @Transactional(readOnly = true)
    public RecommendationResponse getFrequentlyBoughtTogether(UUID productId, Integer limit) {
        int resolvedLimit = resolveLimit(limit);

        productReferenceQuery.findActiveProductWithBrandAndCategory(productId)
                .orElseThrow(() -> new com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException(
                        "Product not found"
                ));

        String cacheKey = recommendationCacheService.productFrequentlyBoughtTogetherKey(
                productId,
                resolvedLimit
        );

        Optional<RecommendationResponse> cachedResponse =
                recommendationCacheService.get(cacheKey, RecommendationResponse.class);

        if (cachedResponse.isPresent()) {
            RecommendationResponse cached = cachedResponse.get();
            List<RecommendationItemResponse> refreshedItems = refreshCachedItems(cached.items());
            if (cached.items() != null
                    && !cached.items().isEmpty()
                    && refreshedItems.size() == cached.items().size()) {
                return new RecommendationResponse(cached.sourceProductId(), cached.type(), refreshedItems);
            }
        }

        List<AssociationRule> rules = associationRuleRepository.findActiveRulesByAntecedentProductId(
                productId,
                PageRequest.of(0, candidateLimit(resolvedLimit))
        );

        RecommendationResponse response;

        if (!rules.isEmpty()) {
            List<RecommendationItemResponse> items = toRecommendationItems(
                    rules,
                    "Customers often buy this product together",
                    resolvedLimit
            );

            if (items.isEmpty()) {
                items = buildProductDetailFallbackItems(productId, resolvedLimit);
            }

            response = new RecommendationResponse(
                    productId,
                    FREQUENTLY_BOUGHT_TOGETHER,
                    items
            );
        } else {
            List<RecommendationItemResponse> fallbackItems = buildProductDetailFallbackItems(
                    productId,
                    resolvedLimit
            );

            response = new RecommendationResponse(
                    productId,
                    FREQUENTLY_BOUGHT_TOGETHER,
                    fallbackItems
            );
        }

        recommendationCacheService.put(cacheKey, response);

        return response;
    }

    @Transactional(readOnly = true)
    public CartRecommendationResponse getCartRecommendations(CartOwner owner, Integer limit) {
        int resolvedLimit = resolveLimit(limit);

        Optional<Cart> optionalCart = findCart(owner);

        if (optionalCart.isEmpty()) {
            return new CartRecommendationResponse(
                    null,
                    List.of(),
                    CART_RECOMMENDATION,
                    List.of()
            );
        }

        Cart cart = optionalCart.get();

        List<CartItem> cartItems = cartReferenceQuery.findItemsWithVariantAndProduct(cart.getId());

        List<UUID> sourceProductIds = cartItems.stream()
                .map(item -> item.getVariant().getProduct().getId())
                .distinct()
                .toList();

        if (sourceProductIds.isEmpty()) {
            return new CartRecommendationResponse(
                    cart.getId(),
                    List.of(),
                    CART_RECOMMENDATION,
                    List.of()
            );
        }

        String cacheKey = recommendationCacheService.cartRecommendationKey(
                cart.getId(),
                sourceProductIds,
                resolvedLimit
        );

        Optional<CartRecommendationResponse> cachedResponse =
                recommendationCacheService.get(cacheKey, CartRecommendationResponse.class);

        if (cachedResponse.isPresent()) {
            CartRecommendationResponse cached = cachedResponse.get();
            List<RecommendationItemResponse> refreshedItems = refreshCachedItems(cached.items());
            if (cached.items() != null
                    && !cached.items().isEmpty()
                    && refreshedItems.size() == cached.items().size()) {
                return new CartRecommendationResponse(
                        cached.cartId(),
                        cached.sourceProductIds(),
                        cached.type(),
                        refreshedItems
                );
            }
        }

        Set<UUID> productIdsInCart = new HashSet<>(sourceProductIds);

        List<AssociationRule> rules =
                associationRuleRepository.findActiveRulesByAntecedentProductIdIn(sourceProductIds);

        List<AssociationRule> selectedRules = selectBestRulesForCart(
                rules,
                productIdsInCart,
                resolvedLimit
        );

        List<RecommendationItemResponse> items;

        if (!selectedRules.isEmpty()) {
            items = toRecommendationItems(
                    selectedRules,
                    "Recommended based on products in your cart",
                    resolvedLimit
            );
            if (items.isEmpty()) {
                items = buildCartFallbackItems(productIdsInCart, resolvedLimit);
            }
        } else {
            items = buildCartFallbackItems(
                    productIdsInCart,
                    resolvedLimit
            );
        }

        CartRecommendationResponse response = new CartRecommendationResponse(
                cart.getId(),
                sourceProductIds,
                CART_RECOMMENDATION,
                items
        );

        recommendationCacheService.put(cacheKey, response);

        return response;
    }

    private Optional<Cart> findCart(CartOwner owner) {
        return cartReferenceQuery.findCart(owner);
    }

    private List<AssociationRule> selectBestRulesForCart(
            List<AssociationRule> rules,
            Set<UUID> productIdsInCart,
            int limit) {

        Comparator<AssociationRule> rankComparator = associationRuleRankComparator();

        Map<UUID, AssociationRule> bestRuleByConsequentProductId = new LinkedHashMap<>();

        for (AssociationRule rule : rules) {
            UUID recommendedProductId = rule.getConsequentProduct().getId();

            if (productIdsInCart.contains(recommendedProductId)) {
                continue;
            }

            AssociationRule existingRule = bestRuleByConsequentProductId.get(recommendedProductId);

            if (existingRule == null || rankComparator.compare(rule, existingRule) > 0) {
                bestRuleByConsequentProductId.put(recommendedProductId, rule);
            }
        }

        return bestRuleByConsequentProductId.values()
                .stream()
                .sorted(rankComparator.reversed())
                .limit(limit)
                .toList();
    }

    private Comparator<AssociationRule> associationRuleRankComparator() {
        return Comparator
                .comparingDouble(AssociationRule::getConfidence)
                .thenComparingDouble(AssociationRule::getLift)
                .thenComparingDouble(AssociationRule::getSupport)
                .thenComparingLong(AssociationRule::getPairCount);
    }

    private List<RecommendationItemResponse> buildProductDetailFallbackItems(UUID productId, int limit) {
        Optional<Product> optionalSourceProduct = productReferenceQuery.findActiveProductWithBrandAndCategory(productId);

        if (optionalSourceProduct.isEmpty()) {
            return List.of();
        }

        Product sourceProduct = optionalSourceProduct.get();

        List<Product> fallbackProducts = new ArrayList<>(
                productReferenceQuery.findSimilarActiveProducts(
                        sourceProduct.getCategory().getId(),
                        sourceProduct.getBrand().getId(),
                        sourceProduct.getId(),
                        PageRequest.of(0, candidateLimit(limit))
                )
        );

        if (fallbackProducts.size() < limit) {
            Set<UUID> excludedProductIds = new LinkedHashSet<>();
            excludedProductIds.add(sourceProduct.getId());
            fallbackProducts.stream()
                    .map(Product::getId)
                    .forEach(excludedProductIds::add);

            List<Product> extraProducts = productReferenceQuery.findActiveFallbackProductsExcluding(
                    excludedProductIds,
                    PageRequest.of(0, candidateLimit(limit - fallbackProducts.size()))
            );

            fallbackProducts.addAll(extraProducts);
        }

        return toFallbackItems(
                fallbackProducts,
                "Fallback: similar active products",
                limit
        );
    }

    private List<RecommendationItemResponse> buildCartFallbackItems(
            Set<UUID> productIdsInCart,
            int limit) {

        if (productIdsInCart.isEmpty()) {
            return List.of();
        }

        List<Product> fallbackProducts = productReferenceQuery.findActiveFallbackProductsExcluding(
                productIdsInCart,
                PageRequest.of(0, candidateLimit(limit))
        );

        return toFallbackItems(
                fallbackProducts,
                "Fallback: active catalog products",
                limit
        );
    }

    private List<RecommendationItemResponse> toRecommendationItems(
            List<AssociationRule> rules,
            String reason,
            int limit) {

        if (rules.isEmpty()) {
            return List.of();
        }

        List<UUID> recommendedProductIds = rules.stream()
                .map(rule -> rule.getConsequentProduct().getId())
                .distinct()
                .toList();

        Map<UUID, List<ProductVariant>> variantsByProduct =
                productReferenceQuery.findVariantsByProductIds(recommendedProductIds)
                        .stream()
                        .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        Map<UUID, List<ProductImage>> imagesByProduct =
                productReferenceQuery.findImagesByProductIds(recommendedProductIds)
                        .stream()
                        .collect(Collectors.groupingBy(image -> image.getProduct().getId()));

        return rules.stream()
                .filter(rule -> hasAvailableVariant(
                        variantsByProduct.getOrDefault(rule.getConsequentProduct().getId(), List.of())
                ))
                .map(rule -> toRecommendationItem(
                        rule,
                        variantsByProduct.getOrDefault(rule.getConsequentProduct().getId(), List.of()),
                        imagesByProduct.getOrDefault(rule.getConsequentProduct().getId(), List.of()),
                        reason
                ))
                .limit(limit)
                .toList();
    }

    private RecommendationItemResponse toRecommendationItem(
            AssociationRule rule,
            List<ProductVariant> variants,
            List<ProductImage> images,
            String reason) {

        Product product = rule.getConsequentProduct();

        ProductListItemResponse productCard = toProductListItem(product, variants, images);

        return new RecommendationItemResponse(
                productCard,
                rule.getSupport(),
                rule.getConfidence(),
                rule.getLift(),
                rule.getPairCount(),
                reason
        );
    }

    private List<RecommendationItemResponse> toFallbackItems(
            List<Product> products,
            String reason,
            int limit) {

        if (products.isEmpty()) {
            return List.of();
        }

        List<UUID> productIds = products.stream()
                .map(Product::getId)
                .distinct()
                .toList();

        Map<UUID, List<ProductVariant>> variantsByProduct =
                productReferenceQuery.findVariantsByProductIds(productIds)
                        .stream()
                        .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        Map<UUID, List<ProductImage>> imagesByProduct =
                productReferenceQuery.findImagesByProductIds(productIds)
                        .stream()
                        .collect(Collectors.groupingBy(image -> image.getProduct().getId()));

        return products.stream()
                .filter(product -> hasAvailableVariant(
                        variantsByProduct.getOrDefault(product.getId(), List.of())
                ))
                .map(product -> new RecommendationItemResponse(
                        toProductListItem(
                                product,
                                variantsByProduct.getOrDefault(product.getId(), List.of()),
                                imagesByProduct.getOrDefault(product.getId(), List.of())
                        ),
                        0.0,
                        0.0,
                        0.0,
                        0L,
                        reason
                ))
                .limit(limit)
                .toList();
    }

    private ProductListItemResponse toProductListItem(
            Product product,
            List<ProductVariant> variants,
            List<ProductImage> images) {

        List<BigDecimal> visiblePrices = variants.stream()
                .filter(this::isAvailableVariant)
                .map(ProductVariant::getPrice)
                .toList();

        BigDecimal minPrice = visiblePrices.stream()
                .min(Comparator.naturalOrder())
                .orElse(null);

        BigDecimal maxPrice = visiblePrices.stream()
                .max(Comparator.naturalOrder())
                .orElse(null);
        int availableQuantity = variants.stream()
                .filter(this::isAvailableVariant)
                .mapToInt(v -> Math.max(0, v.getStockQuantity() - v.getReservedQuantity()))
                .sum();
        ProductVariant saleVariant = variants.stream()
                .filter(this::isAvailableVariant)
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
                ThumbnailResolver.resolve(images),
                minPrice,
                maxPrice,
                salePrice,
                compareAtPrice,
                discountPercent,
                availableQuantity,
                product.getStatus(),
                product.getProductType()
        );
    }

    private CatalogRefResponse toRef(Brand brand) {
        return brand == null ? null : new CatalogRefResponse(brand.getId(), brand.getName());
    }

    private CatalogRefResponse toRef(Category category) {
        return category == null ? null : new CatalogRefResponse(category.getId(), category.getName());
    }

    private List<RecommendationItemResponse> refreshCachedItems(
            List<RecommendationItemResponse> cachedItems) {
        if (cachedItems == null || cachedItems.isEmpty()) {
            return List.of();
        }

        List<UUID> productIds = cachedItems.stream()
                .map(item -> item.product().id())
                .distinct()
                .toList();

        Map<UUID, Product> productsById = productReferenceQuery
                .findActiveProductsWithBrandAndCategory(productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        Map<UUID, List<ProductVariant>> variantsByProduct = productReferenceQuery
                .findVariantsByProductIds(productIds)
                .stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        Map<UUID, List<ProductImage>> imagesByProduct = productReferenceQuery
                .findImagesByProductIds(productIds)
                .stream()
                .collect(Collectors.groupingBy(image -> image.getProduct().getId()));

        return cachedItems.stream()
                .filter(item -> productsById.containsKey(item.product().id()))
                .filter(item -> hasAvailableVariant(
                        variantsByProduct.getOrDefault(item.product().id(), List.of())
                ))
                .map(item -> {
                    UUID productId = item.product().id();
                    return new RecommendationItemResponse(
                            toProductListItem(
                                    productsById.get(productId),
                                    variantsByProduct.getOrDefault(productId, List.of()),
                                    imagesByProduct.getOrDefault(productId, List.of())
                            ),
                            item.support(),
                            item.confidence(),
                            item.lift(),
                            item.pairCount(),
                            item.reason()
                    );
                })
                .toList();
    }

    private boolean hasAvailableVariant(List<ProductVariant> variants) {
        return variants.stream().anyMatch(this::isAvailableVariant);
    }

    private boolean isAvailableVariant(ProductVariant variant) {
        return variant.getStatus() == VariantStatus.ACTIVE
                && variant.getStockQuantity() - variant.getReservedQuantity() > 0;
    }

    private int candidateLimit(int requestedLimit) {
        return Math.min(requestedLimit * CANDIDATE_MULTIPLIER, MAX_LIMIT * CANDIDATE_MULTIPLIER);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new BusinessRuleException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be greater than or equal to 1"
            );
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
