package com.dunghaiquyen.ecommerce.modules.cart.service;

import com.dunghaiquyen.ecommerce.modules.cart.api.CartReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartOwner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartReferenceService implements CartReferenceQuery {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    public CartReferenceService(CartRepository cartRepository, CartItemRepository cartItemRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Cart> findCart(CartOwner owner) {
        return owner.isUser()
                ? cartRepository.findByUserId(owner.userId())
                : cartRepository.findBySessionId(owner.sessionId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartItem> findItemsWithVariantAndProduct(UUID cartId) {
        return cartItemRepository.findAllByCartIdWithVariantAndProduct(cartId);
    }
}
