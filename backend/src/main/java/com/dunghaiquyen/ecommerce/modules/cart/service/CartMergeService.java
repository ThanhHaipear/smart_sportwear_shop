package com.dunghaiquyen.ecommerce.modules.cart.service;

import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.user.api.UserReferenceQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Called from AuthService right after register/login succeeds (PHASE1_SPEC.md
 * 6.5: "Sau login/register thành công, backend phải tự động merge guest cart
 * vào user cart"). Kept as its own small service rather than folded into
 * CartService so AuthService only depends on this one narrow operation, not
 * the whole request-cycle cart API surface.
 */
@Service
public class CartMergeService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserReferenceQuery userReferenceQuery;

    public CartMergeService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            UserReferenceQuery userReferenceQuery) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.userReferenceQuery = userReferenceQuery;
    }

    @Transactional
    public void mergeGuestCartIntoUserCart(String guestSessionId, UUID userId) {
        if (guestSessionId == null || guestSessionId.isBlank()) {
            return;
        }
        Optional<Cart> guestCartOpt = cartRepository.findBySessionId(guestSessionId);
        if (guestCartOpt.isEmpty()) {
            return;
        }
        Cart guestCart = guestCartOpt.get();

        // Always merge item-by-item, never re-point the whole guest cart at the
        // user (even when the user has no cart yet): re-pointing would carry
        // over items unchanged, with no chance to drop ones that are no longer
        // purchasable (variant gone INACTIVE, product no longer ACTIVE) since
        // they were added to the guest cart. Lazily resolved on the first valid
        // item so a guest cart whose items are ALL invalid never forces an empty
        // user cart row into existence.
        List<CartItem> guestItems = cartItemRepository.findAllByCartIdWithVariantAndProduct(guestCart.getId());
        Cart userCart = null;
        for (CartItem guestItem : guestItems) {
            if (!isAvailableForCart(guestItem.getVariant())) {
                continue;
            }
            if (userCart == null) {
                userCart = findOrCreateUserCart(userId);
            }
            mergeOneItem(userCart, guestItem);
        }

        // Explicit delete per spec wording ("xóa guest cart sau merge") - cart_items
        // cascade via the FK (on delete cascade), so this also clears the rows just
        // merged out of the guest cart (valid or dropped, all gone either way).
        cartRepository.delete(guestCart);
    }

    /**
     * Same rule as CartService#validateAddable, duplicated here on purpose: that
     * method throws to reject a live add-to-cart request, this one needs a
     * plain boolean to silently drop a stale guest item instead - reaching into
     * CartService's request-cycle API for a two-line check was not worth the
     * coupling. If this rule ever changes, update both.
     */
    private boolean isAvailableForCart(ProductVariant variant) {
        return variant.getStatus() != VariantStatus.INACTIVE
                && variant.getProduct().getStatus() == ProductStatus.ACTIVE;
    }

    private Cart findOrCreateUserCart(UUID userId) {
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(userReferenceQuery.getReference(userId));
            try {
                return cartRepository.save(cart);
            } catch (DataIntegrityViolationException ex) {
                // Race: a concurrent request already created this user's cart
                // (uq_carts_user_id) - use that one instead.
                return cartRepository.findByUserId(userId).orElseThrow(() -> ex);
            }
        });
    }

    private void mergeOneItem(Cart userCart, CartItem guestItem) {
        ProductVariant variant = guestItem.getVariant();
        int available = variant.getStockQuantity() - variant.getReservedQuantity();

        Optional<CartItem> existing = cartItemRepository.findByCartIdAndVariantId(userCart.getId(), variant.getId());
        if (existing.isPresent()) {
            CartItem userItem = existing.get();
            // Clamp to available stock rather than failing the whole login/register
            // over a cart quantity conflict - see AuthService wiring comment for why
            // failing auth itself would be the wrong tradeoff here. If the user's
            // existing quantity alone already exceeded available stock (a pre-existing
            // state from a later stock decrease, not caused by this merge), clamping
            // down to available actually fixes that rather than preserving it.
            int merged = Math.min(userItem.getQuantity() + guestItem.getQuantity(), available);
            if (merged <= 0) {
                cartItemRepository.delete(userItem);
            } else {
                userItem.setQuantity(merged);
                cartItemRepository.save(userItem);
            }
        } else {
            int clamped = Math.min(guestItem.getQuantity(), available);
            if (clamped > 0) {
                CartItem newItem = new CartItem();
                newItem.setCart(userCart);
                newItem.setVariant(variant);
                newItem.setQuantity(clamped);
                cartItemRepository.save(newItem);
            }
            // clamped <= 0 (no stock left at all): drop the guest item silently rather
            // than carrying a zero-quantity row forward (quantity > 0 is a DB check
            // constraint, not just a display convention).
        }
    }
}
