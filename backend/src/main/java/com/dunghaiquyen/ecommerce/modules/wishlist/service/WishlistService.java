package com.dunghaiquyen.ecommerce.modules.wishlist.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.modules.user.api.UserReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.wishlist.dto.AddWishlistItemRequest;
import com.dunghaiquyen.ecommerce.modules.wishlist.dto.WishlistItemResponse;
import com.dunghaiquyen.ecommerce.modules.wishlist.dto.WishlistResponse;
import com.dunghaiquyen.ecommerce.modules.wishlist.entity.Wishlist;
import com.dunghaiquyen.ecommerce.modules.wishlist.entity.WishlistItem;
import com.dunghaiquyen.ecommerce.modules.wishlist.repository.WishlistItemRepository;
import com.dunghaiquyen.ecommerce.modules.wishlist.repository.WishlistRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3 already enforces "max 1 wishlist per user" (uq_wishlists_user_id) and
 * "no duplicate product per wishlist" (uq_wishlist_items) at the DB level -
 * the application-layer checks below exist only to return clean 404/409/422
 * responses instead of letting either race fall through to a raw constraint
 * violation.
 */
@Service
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final UserReferenceQuery userReferenceQuery;

    public WishlistService(
            WishlistRepository wishlistRepository,
            WishlistItemRepository wishlistItemRepository,
            ProductRepository productRepository,
            ProductImageRepository imageRepository,
            UserReferenceQuery userReferenceQuery) {
        this.wishlistRepository = wishlistRepository;
        this.wishlistItemRepository = wishlistItemRepository;
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.userReferenceQuery = userReferenceQuery;
    }

    /**
     * Deliberately does NOT lazily create a wishlist row here, unlike
     * CartService.getCart(): lazy-creation is scoped to "when an item is
     * added" only. A user who has never added anything just gets an empty,
     * id-less response.
     */
    @Transactional(readOnly = true)
    public WishlistResponse getWishlist(UUID userId) {
        Optional<Wishlist> wishlist = wishlistRepository.findByUserId(userId);
        if (wishlist.isEmpty()) {
            return new WishlistResponse(null, List.of());
        }
        return assemble(wishlist.get());
    }

    @Transactional
    public WishlistResponse addItem(UUID userId, AddWishlistItemRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Product is not available");
        }

        Wishlist wishlist = findOrCreateWishlist(userId);
        if (wishlistItemRepository.existsByWishlistIdAndProductId(wishlist.getId(), product.getId())) {
            throw new BusinessRuleException("Product already in wishlist");
        }

        WishlistItem item = new WishlistItem();
        item.setWishlist(wishlist);
        item.setProduct(product);
        try {
            wishlistItemRepository.save(item);
        } catch (DataIntegrityViolationException ex) {
            // Race: a concurrent request added the same (wishlist, product) pair
            // first (uq_wishlist_items). Unlike CartService's merge-on-race, this
            // feature requires duplicates to be rejected, so surface it as the
            // same clean conflict a sequential request would have gotten.
            if (wishlistItemRepository.existsByWishlistIdAndProductId(wishlist.getId(), product.getId())) {
                throw new BusinessRuleException("Product already in wishlist");
            }
            throw ex;
        }
        return assemble(wishlist);
    }

    @Transactional
    public WishlistResponse removeItem(UUID userId, UUID productId) {
        Wishlist wishlist = wishlistRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wishlist item not found"));
        WishlistItem item = wishlistItemRepository.findByWishlistIdAndProductId(wishlist.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Wishlist item not found"));
        wishlistItemRepository.delete(item);
        return assemble(wishlist);
    }

    private Wishlist findOrCreateWishlist(UUID userId) {
        return wishlistRepository.findByUserId(userId).orElseGet(() -> createWishlist(userId));
    }

    private Wishlist createWishlist(UUID userId) {
        Wishlist wishlist = new Wishlist();
        wishlist.setUser(userReferenceQuery.getReference(userId));
        try {
            return wishlistRepository.save(wishlist);
        } catch (DataIntegrityViolationException ex) {
            // Race: a concurrent request for the same user already created the
            // wishlist (uq_wishlists_user_id) - use that one instead.
            return wishlistRepository.findByUserId(userId).orElseThrow(() -> ex);
        }
    }

    private WishlistResponse assemble(Wishlist wishlist) {
        List<WishlistItem> items = wishlistItemRepository.findAllByWishlistIdWithProduct(wishlist.getId());
        Map<UUID, String> thumbnailByProductId = resolveThumbnails(items);

        List<WishlistItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(item, thumbnailByProductId))
                .toList();
        return new WishlistResponse(wishlist.getId(), itemResponses);
    }

    private Map<UUID, String> resolveThumbnails(List<WishlistItem> items) {
        List<UUID> productIds = items.stream().map(item -> item.getProduct().getId()).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(image -> image.getProduct().getId()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ThumbnailResolver.resolve(e.getValue())));
    }

    private WishlistItemResponse toItemResponse(WishlistItem item, Map<UUID, String> thumbnailByProductId) {
        Product product = item.getProduct();
        return new WishlistItemResponse(
                item.getId(),
                product.getId(),
                product.getName(),
                thumbnailByProductId.get(product.getId()),
                item.getCreatedAt());
    }
}
