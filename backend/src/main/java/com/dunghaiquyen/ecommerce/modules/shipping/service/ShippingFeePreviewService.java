package com.dunghaiquyen.ecommerce.modules.shipping.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.AppShippingProperties;
import com.dunghaiquyen.ecommerce.modules.address.api.AddressOwnershipQuery;
import com.dunghaiquyen.ecommerce.modules.order.service.OrderService;
import com.dunghaiquyen.ecommerce.modules.shipping.dto.ShippingFeePreviewRequest;
import com.dunghaiquyen.ecommerce.modules.shipping.dto.ShippingFeePreviewResponse;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethod;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethodStatus;
import com.dunghaiquyen.ecommerce.modules.shipping.repository.ShippingMethodRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately thin, same discipline as CheckoutPreviewService: the actual
 * shippingFee number is never computed here - it is read straight off
 * OrderService.calculateShippingFee(subtotal), the exact method
 * createOrderFromCart itself calls, so this preview can never disagree with
 * what checkout would actually charge. subtotal comes from the caller's
 * CURRENT cart (OrderService.checkCartLines) since the request intentionally
 * carries no subtotal/cart payload of its own (see ShippingFeePreviewRequest).
 *
 * <p>shippingMethod.baseFee is surfaced purely as method metadata - it does
 * NOT feed into shippingFee this phase (see ShippingFeePreviewResponse's
 * javadoc for the tradeoff this accepts).
 */
@Service
public class ShippingFeePreviewService {

    private final OrderService orderService;
    private final AddressOwnershipQuery addressOwnershipQuery;
    private final ShippingMethodRepository shippingMethodRepository;
    private final AppShippingProperties shippingProperties;

    public ShippingFeePreviewService(
            OrderService orderService,
            AddressOwnershipQuery addressOwnershipQuery,
            ShippingMethodRepository shippingMethodRepository,
            AppShippingProperties shippingProperties) {
        this.orderService = orderService;
        this.addressOwnershipQuery = addressOwnershipQuery;
        this.shippingMethodRepository = shippingMethodRepository;
        this.shippingProperties = shippingProperties;
    }

    @Transactional(readOnly = true)
    public ShippingFeePreviewResponse preview(UUID userId, ShippingFeePreviewRequest request) {
        addressOwnershipQuery.requireOwnedAddress(userId, request.addressId());

        ShippingMethod method = shippingMethodRepository.findById(request.shippingMethodId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipping method not found"));
        if (method.getStatus() != ShippingMethodStatus.ACTIVE) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Shipping method is not available");
        }

        OrderService.CartLinesCheckResult linesResult = orderService.checkCartLines(userId);
        if (linesResult.lines().isEmpty()) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty");
        }
        BigDecimal subtotal = linesResult.validSubtotal();
        BigDecimal shippingFee = orderService.calculateShippingFee(subtotal);

        BigDecimal threshold = shippingProperties.freeShippingThreshold();
        boolean freeShippingApplied = threshold != null && subtotal.compareTo(threshold) >= 0;

        LocalDate today = LocalDate.now();
        LocalDate from = method.getEstimatedDaysMin() != null ? today.plusDays(method.getEstimatedDaysMin()) : null;
        LocalDate to = method.getEstimatedDaysMax() != null ? today.plusDays(method.getEstimatedDaysMax()) : null;

        return new ShippingFeePreviewResponse(
                request.addressId(),
                ShippingMethodService.toResponse(method),
                subtotal,
                shippingFee,
                freeShippingApplied,
                from,
                to);
    }
}
