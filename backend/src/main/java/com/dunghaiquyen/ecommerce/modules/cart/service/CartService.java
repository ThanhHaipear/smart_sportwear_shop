package com.dunghaiquyen.ecommerce.modules.cart.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.cart.dto.AddCartItemRequest;
import com.dunghaiquyen.ecommerce.modules.cart.dto.CartItemResponse;
import com.dunghaiquyen.ecommerce.modules.cart.dto.CartResponse;
import com.dunghaiquyen.ecommerce.modules.cart.dto.UpdateCartItemRequest;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartOwner;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.api.ProductReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.product.util.ThumbnailResolver;
import com.dunghaiquyen.ecommerce.modules.user.api.UserReferenceQuery;
import java.math.BigDecimal;
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
 * No product/price data is snapshotted onto cart_items (ERD_PHASE1.md: only
 * order_items snapshot, carts are live) - every response here is assembled
 * fresh from product_variants/products/product_images at read time.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductReferenceQuery productReferenceQuery;
    private final UserReferenceQuery userReferenceQuery;

    public CartService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            ProductReferenceQuery productReferenceQuery,
            UserReferenceQuery userReferenceQuery) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productReferenceQuery = productReferenceQuery;
        this.userReferenceQuery = userReferenceQuery;
    }

    @Transactional
    public CartResponse getCart(CartOwner owner) {
        return assemble(findOrCreateCart(owner));
    }

    @Transactional
    public CartResponse addItem(CartOwner owner, AddCartItemRequest request) {
        Cart cart = lockCart(findOrCreateCart(owner));
        ProductVariant variant = productReferenceQuery.findVariant(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found"));
        validateAddable(variant);
        int available = availableQuantity(variant);

        Optional<CartItem> existing = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId());
        if (existing.isPresent()) {
            CartItem item = existing.get();
            int merged = item.getQuantity() + request.quantity();
            requireWithinStock(merged, available);
            item.setQuantity(merged);
            cartItemRepository.save(item);
        } else {
            requireWithinStock(request.quantity(), available);
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setVariant(variant);
            item.setQuantity(request.quantity());
            try {
                cartItemRepository.save(item);
            } catch (DataIntegrityViolationException ex) {
                // Race: another request inserted the same (cart, variant) pair first
                // (uq_cart_items_cart_variant). Self-heal by merging into that row
                // instead of failing the request outright.
                CartItem raced = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())
                        .orElseThrow(() -> ex);
                int merged = raced.getQuantity() + request.quantity();
                requireWithinStock(merged, available);
                raced.setQuantity(merged);
                cartItemRepository.save(raced);
            }
        }
        return assemble(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(CartOwner owner, UUID itemId, UpdateCartItemRequest request) {
        Cart cart = lockCart(findExistingCart(owner));
        CartItem item = findOwnedItem(cart, itemId);
        int available = availableQuantity(item.getVariant());
        requireWithinStock(request.quantity(), available);
        item.setQuantity(request.quantity());
        cartItemRepository.save(item);
        return assemble(cart);
    }

    @Transactional
    public CartResponse removeItem(CartOwner owner, UUID itemId) {
        Cart cart = lockCart(findExistingCart(owner));
        CartItem item = findOwnedItem(cart, itemId);
        cartItemRepository.delete(item);
        return assemble(cart);
    }

    /**
     * PATCH/DELETE must not create a cart as a side effect of looking one up:
     * unlike GET/POST, which legitimately need a cart to exist (or to be
     * created lazily) to do their job, a caller with no cart at all cannot
     * possibly own any item, so this is a plain 404 with no write. Using
     * findOrCreateCart here would silently insert an empty cart row for every
     * PATCH/DELETE on a bogus item id - caught by testing, not by inspection.
     */
    private Cart findExistingCart(CartOwner owner) {
        Optional<Cart> cart = owner.isUser()
                ? cartRepository.findByUserId(owner.userId())
                : cartRepository.findBySessionId(owner.sessionId());
        return cart.orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
    }

    private Cart lockCart(Cart cart) {
        return cartRepository.findByIdForUpdate(cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
    }

    /**
     * Ownership is checked here, not just implied by the endpoint shape: an item
     * id is a global identifier, nothing about the URL stops a caller from
     * passing someone else's item id. Returns 404 (not 403) either way - whether
     * the id does not exist at all or belongs to a different cart looks
     * identical to the caller, so this never confirms that a given id belongs
     * to someone else.
     */
    private CartItem findOwnedItem(Cart cart, UUID itemId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
        if (!item.getCart().getId().equals(cart.getId())) {
            throw new ResourceNotFoundException("Cart item not found");
        }
        return item;
    }

    private void validateAddable(ProductVariant variant) {
        if (variant.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Variant is not available");
        }
        if (variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Product is not available");
        }
    }

    private void requireWithinStock(int requestedQuantity, int available) {
        if (requestedQuantity > available) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Quantity exceeds available stock");
        }
    }

    private int availableQuantity(ProductVariant variant) {
        return variant.getStockQuantity() - variant.getReservedQuantity();
    }

    private Cart findOrCreateCart(CartOwner owner) {
        if (owner.isUser()) {
            return cartRepository.findByUserId(owner.userId()).orElseGet(() -> createCart(owner));
        }
        return cartRepository.findBySessionId(owner.sessionId()).orElseGet(() -> createCart(owner));
    }

    private Cart createCart(CartOwner owner) {
        Cart cart = new Cart();
        if (owner.isUser()) {
            cart.setUser(userReferenceQuery.getReference(owner.userId()));
        } else {
            cart.setSessionId(owner.sessionId());
        }
        try {
            return cartRepository.save(cart);
        } catch (DataIntegrityViolationException ex) {
            // Race: a concurrent request for the same identity already created the
            // cart (uq_carts_user_id / uq_carts_session_id) - use that one instead.
            Optional<Cart> winner = owner.isUser()
                    ? cartRepository.findByUserId(owner.userId())
                    : cartRepository.findBySessionId(owner.sessionId());
            return winner.orElseThrow(() -> ex);
        }
    }

    private CartResponse assemble(Cart cart) {
        List<CartItem> items = cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId());
        Map<UUID, String> thumbnailByProductId = resolveThumbnails(items);

        List<CartItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(item, thumbnailByProductId))
                .toList();
        BigDecimal subtotal = itemResponses.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(cart.getId(), itemResponses, subtotal);
    }

    private Map<UUID, String> resolveThumbnails(List<CartItem> items) {
        List<UUID> productIds = items.stream()
                .map(item -> item.getVariant().getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productReferenceQuery.findImagesByProductIds(productIds).stream()
                .collect(Collectors.groupingBy(image -> image.getProduct().getId()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ThumbnailResolver.resolve(e.getValue())));
    }

    private CartItemResponse toItemResponse(CartItem item, Map<UUID, String> thumbnailByProductId) {
        ProductVariant variant = item.getVariant();
        Product product = variant.getProduct();
        BigDecimal lineTotal = variant.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(
                item.getId(),
                variant.getId(),
                product.getId(),
                product.getName(),
                variant.getSku(),
                variant.getSize(),
                variant.getColor(),
                variant.getPrice(),
                item.getQuantity(),
                lineTotal,
                thumbnailByProductId.get(product.getId()));
    }
}
