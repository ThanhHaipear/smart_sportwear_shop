package com.dunghaiquyen.ecommerce.modules.inventory.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.response.PageMeta;
import com.dunghaiquyen.ecommerce.common.time.AppTimeZone;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.AdjustStockRequest;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.ExportStockRequest;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.ImportStockRequest;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.InventoryItemResponse;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.InventoryListQuery;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.InventoryTransactionListQuery;
import com.dunghaiquyen.ecommerce.modules.inventory.dto.InventoryTransactionResponse;
import com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransaction;
import com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransactionType;
import com.dunghaiquyen.ecommerce.modules.inventory.repository.InventoryTransactionRepository;
import com.dunghaiquyen.ecommerce.modules.inventory.repository.spec.InventoryTransactionSpecifications;
import com.dunghaiquyen.ecommerce.modules.inventory.repository.spec.InventoryVariantSpecifications;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.product.dto.CatalogRefResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole owner of ProductVariant.stockQuantity/reservedQuantity writes (see the
 * class comment on ProductVariant) - every mutation here is paired with an
 * InventoryTransaction audit row in the same transaction.
 *
 * Two locking shapes on purpose:
 * - {@link #recordReserve} takes an ALREADY pessimistic-locked variant: the
 *   caller (OrderService.createOrder) locks+validates every cart line up
 *   front in one pass before creating anything, then reuses that same
 *   managed entity here. Postgres row locks live for the whole transaction,
 *   not just one statement, so re-locking would just be a redundant round
 *   trip, not extra safety.
 * - {@link #confirmDeduct}, {@link #release}, and the manual import/export/
 *   adjust methods below all take a variant id and lock it themselves - none
 *   of their callers have a pre-existing lock to reuse.
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProductVariantRepository variantRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final ProductImageRepository imageRepository;
    private final InventoryRealtimeService realtimeService;

    public InventoryService(
            ProductVariantRepository variantRepository,
            InventoryTransactionRepository transactionRepository,
            ProductImageRepository imageRepository,
            InventoryRealtimeService realtimeService) {
        this.variantRepository = variantRepository;
        this.transactionRepository = transactionRepository;
        this.imageRepository = imageRepository;
        this.realtimeService = realtimeService;
    }

    public record ListResult<T>(List<T> items, PageMeta meta) {
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public void recordReserve(ProductVariant lockedVariant, int quantity, Order order, User actor) {
        int beforeStock = lockedVariant.getStockQuantity();
        int beforeReserved = lockedVariant.getReservedQuantity();
        lockedVariant.setReservedQuantity(beforeReserved + quantity);
        variantRepository.save(lockedVariant);
        log(
                lockedVariant,
                order,
                InventoryTransactionType.ORDER_RESERVE,
                quantity,
                beforeStock,
                beforeReserved,
                lockedVariant.getReservedQuantity(),
                actor,
                "Order " + order.getOrderCode() + " reserved stock");
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public void confirmDeduct(UUID variantId, int quantity, Order order, User actor) {
        ProductVariant variant = lockVariant(variantId);
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();
        variant.setStockQuantity(beforeStock - quantity);
        variant.setReservedQuantity(beforeReserved - quantity);
        variantRepository.save(variant);
        log(
                variant,
                order,
                InventoryTransactionType.ORDER_CONFIRM_DEDUCT,
                quantity,
                beforeStock,
                beforeReserved,
                variant.getReservedQuantity(),
                actor,
                "Order " + order.getOrderCode() + " confirmed - stock deducted");
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public void release(UUID variantId, int quantity, Order order, User actor) {
        ProductVariant variant = lockVariant(variantId);
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();
        variant.setReservedQuantity(beforeReserved - quantity);
        variantRepository.save(variant);
        log(
                variant,
                order,
                InventoryTransactionType.ORDER_RELEASE,
                quantity,
                beforeStock,
                beforeReserved,
                variant.getReservedQuantity(),
                actor,
                "Order " + order.getOrderCode() + " cancelled - reserved stock released");
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_PRODUCTS, allEntries = true)
    })
    public void restockReturn(UUID variantId, int quantity, Order order, User actor) {
        ProductVariant variant = lockVariant(variantId);
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();
        variant.setStockQuantity(beforeStock + quantity);
        variantRepository.save(variant);
        log(variant, order, InventoryTransactionType.RETURN_RESTOCK, quantity,
                beforeStock, beforeReserved, beforeReserved, actor,
                "Accepted customer return restocked");
    }

    /** Manual warehouse receipt - never touches reservedQuantity (TASK_BREAKDOWN_PHASE1.md I2). */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public InventoryItemResponse importStock(ImportStockRequest request, User actor) {
        ProductVariant variant = lockVariant(request.variantId());
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();
        variant.setStockQuantity(beforeStock + request.quantity());
        variantRepository.save(variant);
        log(
                variant,
                null,
                InventoryTransactionType.IMPORT,
                request.quantity(),
                beforeStock,
                beforeReserved,
                beforeReserved,
                actor,
                request.note());

        return toItemResponse(variant);
    }

    /**
     * Manual warehouse withdrawal - never touches reservedQuantity (I3). Validated
     * against AVAILABLE quantity (stock - reserved), not raw stock_quantity: an
     * export that ate into reserved units would silently break the promise a
     * pending order's reservation makes, AND could push reservedQuantity above
     * the new stockQuantity, violating the DB check constraint
     * chk_product_variants_reserved_le_stock (a 500 instead of a clean 422).
     * Same invariant used everywhere else stock is checked in this codebase.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public InventoryItemResponse exportStock(ExportStockRequest request, User actor) {
        ProductVariant variant = lockVariant(request.variantId());
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();
        int available = beforeStock - beforeReserved;
        if (request.quantity() > available) {
            throw new BusinessRuleException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient stock to export for " + variant.getSku());
        }
        variant.setStockQuantity(beforeStock - request.quantity());
        variantRepository.save(variant);
        log(
                variant,
                null,
                InventoryTransactionType.EXPORT,
                request.quantity(),
                beforeStock,
                beforeReserved,
                beforeReserved,
                actor,
                request.note());
        return toItemResponse(variant);
    }

    /**
     * Manual correction (I4) - ADJUSTMENT_UP only increases stockQuantity (no
     * validation beyond quantity > 0, already enforced at the DTO level).
     * ADJUSTMENT_DOWN uses the SAME available-quantity guard as
     * {@link #exportStock} and for the same reason (spec only says "không âm",
     * but the DB check constraint ties reservedQuantity to stockQuantity, so
     * "không âm" alone is not sufficient to stay constraint-safe).
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.REPORT_OVERVIEW, allEntries = true),
            @CacheEvict(value = CacheConfig.REPORT_INVENTORY, allEntries = true)
    })
    public InventoryItemResponse adjustStock(AdjustStockRequest request, User actor) {
        if (request.type() != InventoryTransactionType.ADJUSTMENT_UP
                && request.type() != InventoryTransactionType.ADJUSTMENT_DOWN) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid adjustment type");
        }
        ProductVariant variant = lockVariant(request.variantId());
        int beforeStock = variant.getStockQuantity();
        int beforeReserved = variant.getReservedQuantity();

        if (request.type() == InventoryTransactionType.ADJUSTMENT_UP) {
            variant.setStockQuantity(beforeStock + request.quantity());
        } else {
            int available = beforeStock - beforeReserved;
            if (request.quantity() > available) {
                throw new BusinessRuleException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Adjustment would make stock negative for " + variant.getSku());
            }
            variant.setStockQuantity(beforeStock - request.quantity());
        }
        variantRepository.save(variant);
        log(
                variant,
                null,
                request.type(),
                request.quantity(),
                beforeStock,
                beforeReserved,
                beforeReserved,
                actor,
                request.note());
        return toItemResponse(variant);
    }

    @Transactional(readOnly = true)
    public ListResult<InventoryItemResponse> listInventory(InventoryListQuery query) {
        Specification<ProductVariant> spec = InventoryVariantSpecifications.fetchProduct();
        if (query.productId() != null) {
            spec = spec.and(InventoryVariantSpecifications.hasProductId(query.productId()));
        }
        if (query.categoryId() != null) {
            spec = spec.and(InventoryVariantSpecifications.hasCategoryId(query.categoryId()));
        }
        if (query.brandId() != null) {
            spec = spec.and(InventoryVariantSpecifications.hasBrandId(query.brandId()));
        }
        if (query.status() != null) {
            spec = spec.and(InventoryVariantSpecifications.hasStatus(query.status()));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            spec = spec.and(InventoryVariantSpecifications.keywordMatches(query.keyword().trim()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()),
                resolveLimit(query.limit()),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<ProductVariant> page = variantRepository.findAll(spec, pageable);
        Map<UUID, String> thumbnailByProductId = resolveThumbnails(page.getContent());
        List<InventoryItemResponse> items = page.getContent().stream()
                .map(variant -> toItemResponse(variant, thumbnailByProductId.get(variant.getProduct().getId())))
                .toList();
        return new ListResult<>(items, PageMeta.from(page));
    }

    @Transactional(readOnly = true)
    public ListResult<InventoryTransactionResponse> listTransactions(InventoryTransactionListQuery query) {
        Specification<InventoryTransaction> spec = InventoryTransactionSpecifications.fetchVariantAndCreatedBy();
        if (query.variantId() != null) {
            spec = spec.and(InventoryTransactionSpecifications.hasVariantId(query.variantId()));
        }
        if (query.type() != null) {
            spec = spec.and(InventoryTransactionSpecifications.hasType(query.type()));
        }
        if (query.dateFrom() != null) {
            spec = spec.and(InventoryTransactionSpecifications.createdFrom(
                    query.dateFrom().atStartOfDay(AppTimeZone.ZONE).toInstant()));
        }
        if (query.dateTo() != null) {
            spec = spec.and(InventoryTransactionSpecifications.createdTo(
                    query.dateTo().atTime(LocalTime.MAX).atZone(AppTimeZone.ZONE).toInstant()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()),
                resolveLimit(query.limit()),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<InventoryTransaction> page = transactionRepository.findAll(spec, pageable);
        List<InventoryTransactionResponse> items =
                page.getContent().stream().map(this::toTransactionResponse).toList();
        return new ListResult<>(items, PageMeta.from(page));
    }

    private ProductVariant lockVariant(UUID variantId) {
        return variantRepository.findByIdForUpdate(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found"));
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

    /**
     * Thumbnails for a whole page of variants in one query (same "primary else
     * lowest sortOrder" rule as cart/catalog via {@link ThumbnailResolver}) -
     * resolving per variant would be an N+1 over product_images. Products with no
     * image are simply absent from the map, so callers read back a null thumbnail.
     */
    private Map<UUID, String> resolveThumbnails(List<ProductVariant> variants) {
        List<UUID> productIds = variants.stream()
                .map(variant -> variant.getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(image -> image.getProduct().getId()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> ThumbnailResolver.resolve(entry.getValue())));
    }

    private InventoryItemResponse toItemResponse(ProductVariant variant) {
        List<ProductImage> images = imageRepository.findAllByProductIdIn(List.of(variant.getProduct().getId()));
        return toItemResponse(variant, ThumbnailResolver.resolve(images));
    }

    private InventoryItemResponse toItemResponse(ProductVariant variant, String thumbnail) {
        Product product = variant.getProduct();
        return new InventoryItemResponse(
                variant.getId(),
                product.getId(),
                product.getName(),
                thumbnail,
                variant.getSku(),
                variant.getSize(),
                variant.getColor(),
                toRef(product.getCategory()),
                toRef(product.getBrand()),
                variant.getPrice(),
                variant.getStockQuantity(),
                variant.getReservedQuantity(),
                variant.getStockQuantity() - variant.getReservedQuantity(),
                variant.getStatus());
    }

    private InventoryTransactionResponse toTransactionResponse(InventoryTransaction tx) {
        User createdBy = tx.getCreatedBy();
        return new InventoryTransactionResponse(
                tx.getId(),
                tx.getVariant().getId(),
                tx.getVariant().getSku(),
                tx.getOrder() != null ? tx.getOrder().getId() : null,
                tx.getType(),
                tx.getQuantity(),
                tx.getBeforeStockQuantity(),
                tx.getAfterStockQuantity(),
                tx.getBeforeReservedQuantity(),
                tx.getAfterReservedQuantity(),
                tx.getNote(),
                createdBy != null ? createdBy.getId() : null,
                createdBy != null ? createdBy.getFullName() : null,
                tx.getCreatedAt());
    }

    private CatalogRefResponse toRef(Category category) {
        return category == null ? null : new CatalogRefResponse(category.getId(), category.getName());
    }

    private CatalogRefResponse toRef(Brand brand) {
        return brand == null ? null : new CatalogRefResponse(brand.getId(), brand.getName());
    }

    private void log(
            ProductVariant variant,
            Order order,
            InventoryTransactionType type,
            int quantity,
            int beforeStock,
            int beforeReserved,
            int afterReserved,
            User actor,
            String note) {
        InventoryTransaction tx = new InventoryTransaction();
        tx.setVariant(variant);
        tx.setOrder(order);
        tx.setType(type);
        tx.setQuantity(quantity);
        tx.setBeforeStockQuantity(beforeStock);
        tx.setAfterStockQuantity(variant.getStockQuantity());
        tx.setBeforeReservedQuantity(beforeReserved);
        tx.setAfterReservedQuantity(afterReserved);
        tx.setNote(note);
        tx.setCreatedBy(actor);
        transactionRepository.save(tx);
        realtimeService.publishAfterCommit(variant);
    }
}
