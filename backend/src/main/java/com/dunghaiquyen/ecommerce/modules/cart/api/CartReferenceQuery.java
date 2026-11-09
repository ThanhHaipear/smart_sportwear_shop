package com.dunghaiquyen.ecommerce.modules.cart.api;

import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartOwner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transitional Sales API used by Recommendation while cart entities are still shared. */
public interface CartReferenceQuery {

    Optional<Cart> findCart(CartOwner owner);

    List<CartItem> findItemsWithVariantAndProduct(UUID cartId);
}
