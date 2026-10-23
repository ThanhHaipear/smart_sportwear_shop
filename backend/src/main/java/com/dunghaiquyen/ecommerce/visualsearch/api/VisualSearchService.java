package com.dunghaiquyen.ecommerce.visualsearch.api;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VisualSearchService {

    static final int CANDIDATE_LIMIT = 50;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final VisualSearchProperties properties;
    private final VisualSearchClient client;
    private final ProductService productService;
    private final ProductRepository productRepository;

    public VisualSearchService(
            VisualSearchProperties properties,
            VisualSearchClient client,
            ProductService productService,
            ProductRepository productRepository) {
        this.properties = properties;
        this.client = client;
        this.productService = productService;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<VisualSearchResult> search(
            MultipartFile image, int limit, UUID categoryId, Gender gender, BigDecimal minPrice, BigDecimal maxPrice) {
        if (!properties.enabled()) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is disabled");
        }
        if (image == null || image.isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image is required");
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Image must not exceed 5 MB");
        }
        String contentType = normalizeContentType(image.getContentType());
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessRuleException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Image must be JPEG, PNG or WebP");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "minPrice must not exceed maxPrice");
        }

        // Fetch a wider candidate set before commerce filters are applied.
        List<VisualSearchCandidate> candidates = client.search(image, CANDIDATE_LIMIT);
        List<UUID> ids = candidates.stream().map(VisualSearchCandidate::productId).distinct().toList();
        Map<UUID, ProductListItemResponse> products = productService
                .assembleListItemsByIds(ids, ProductStatus.ACTIVE).stream()
                .collect(Collectors.toMap(ProductListItemResponse::id, Function.identity()));
        Map<UUID, com.dunghaiquyen.ecommerce.modules.product.entity.Product> entities =
                productRepository.findAllById(ids).stream().collect(Collectors.toMap(
                        com.dunghaiquyen.ecommerce.modules.product.entity.Product::getId, Function.identity()));

        return candidates.stream()
                .filter(candidate -> products.containsKey(candidate.productId()))
                .filter(candidate -> categoryId == null
                        || entities.get(candidate.productId()).getCategory().getId().equals(categoryId))
                .filter(candidate -> gender == null || entities.get(candidate.productId()).getGender() == gender)
                .filter(candidate -> matchesPrice(products.get(candidate.productId()), minPrice, maxPrice))
                // BR-02: preserve visual similarity inside each group, but surface
                // purchasable products before products whose active variants are sold out.
                .sorted(Comparator.comparing((VisualSearchCandidate candidate) ->
                        !isInStock(entities.get(candidate.productId()))))
                .limit(limit)
                .map(candidate -> new VisualSearchResult(
                        products.get(candidate.productId()), candidate.imageId(),
                        candidate.matchedImageUrl(), candidate.similarity()))
                .toList();
    }

    private static boolean isInStock(com.dunghaiquyen.ecommerce.modules.product.entity.Product product) {
        return product.getVariants().stream().anyMatch(variant ->
                variant.getStatus() == com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus.ACTIVE
                        && variant.getStockQuantity() > variant.getReservedQuantity());
    }

    private static boolean matchesPrice(ProductListItemResponse product, BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && (product.maxPrice() == null || product.maxPrice().compareTo(minPrice) < 0)) {
            return false;
        }
        return maxPrice == null || (product.minPrice() != null && product.minPrice().compareTo(maxPrice) <= 0);
    }

    private static String normalizeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int parameters = value.indexOf(';');
        return (parameters >= 0 ? value.substring(0, parameters) : value).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
