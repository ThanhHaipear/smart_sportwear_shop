package com.dunghaiquyen.ecommerce.modules.checkout.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.checkout.dto.CheckoutItemPreview;
import com.dunghaiquyen.ecommerce.modules.checkout.dto.CheckoutPreviewRequest;
import com.dunghaiquyen.ecommerce.modules.checkout.dto.CheckoutPreviewResponse;
import com.dunghaiquyen.ecommerce.modules.order.service.OrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A thin, read-only preview of what checkout will charge, computed from the exact
 * same rules the real order uses: OrderService.checkCartLines for line validity,
 * OrderService.calculateShippingFee for shipping — so preview and
 * createOrderFromCart can never disagree on a number.
 */
@Service
public class CheckoutPreviewService {

    private final OrderService orderService;
    private final AddressRepository addressRepository;

    public CheckoutPreviewService(OrderService orderService, AddressRepository addressRepository) {
        this.orderService = orderService;
        this.addressRepository = addressRepository;
    }

    /**
     * Empty cart is treated exactly like createOrderFromCart treats it — same 422
     * status and message — so a client that handles the real checkout's empty-cart
     * error handles this one identically.
     */
    @Transactional(readOnly = true)
    public CheckoutPreviewResponse preview(UUID userId, CheckoutPreviewRequest request) {
        boolean buyNow = request.buyNowVariantId() != null;
        if (buyNow && request.cartItemIds() != null && !request.cartItemIds().isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose either buy now or cart checkout");
        }
        OrderService.CartLinesCheckResult linesResult = buyNow
                ? orderService.checkBuyNowLine(request.buyNowVariantId(), request.buyNowQuantity())
                : orderService.checkCartLines(userId, request.cartItemIds());
        if (linesResult.lines().isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty");
        }

        if (request.addressId() != null) {
            addressRepository.findById(request.addressId())
                    .filter(a -> a.getUser().getId().equals(userId))
                    .orElseThrow(() -> new ResourceNotFoundException("Address not found"));
        }

        boolean allItemsValid = linesResult.allValid();
        BigDecimal subtotal = linesResult.validSubtotal();

        // Combo/promotion pricing is deferred until its V2 schema exists.
        BigDecimal discount = BigDecimal.ZERO;

        BigDecimal shippingFee = orderService.calculateShippingFee(subtotal);
        BigDecimal total = subtotal.add(shippingFee).subtract(discount);

        List<CheckoutItemPreview> items =
                linesResult.lines().stream().map(this::toItemPreview).toList();

        return new CheckoutPreviewResponse(items, subtotal, discount, shippingFee, total, allItemsValid);
    }

    private CheckoutItemPreview toItemPreview(OrderService.CartLineCheck line) {
        return new CheckoutItemPreview(
                line.variantId(),
                line.productId(),
                line.productName(),
                line.sku(),
                line.quantity(),
                line.unitPrice(),
                line.lineTotal(),
                line.valid(),
                line.errorMessage());
    }
}
