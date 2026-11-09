package com.dunghaiquyen.ecommerce.modules.order.service;

import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.order.api.SalesProductUsage;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderItemRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesProductUsageService implements SalesProductUsage {

    private final CartItemRepository cartItemRepository;
    private final OrderItemRepository orderItemRepository;

    public SalesProductUsageService(
            CartItemRepository cartItemRepository, OrderItemRepository orderItemRepository) {
        this.cartItemRepository = cartItemRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOrderHistory(List<UUID> variantIds) {
        return orderItemRepository.existsByVariantIdIn(variantIds);
    }

    @Override
    @Transactional
    public void deleteCartItems(List<UUID> variantIds) {
        cartItemRepository.deleteAllByVariantIdIn(variantIds);
    }
}
