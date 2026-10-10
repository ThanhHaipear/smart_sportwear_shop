package com.dunghaiquyen.ecommerce.modules.order.controller;

import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import com.dunghaiquyen.ecommerce.common.security.CustomUserDetails;
import com.dunghaiquyen.ecommerce.modules.order.dto.AdminOrderListQuery;
import com.dunghaiquyen.ecommerce.modules.order.dto.AdminOrderResponse;
import com.dunghaiquyen.ecommerce.modules.order.dto.PackingSlipBatchRequest;
import com.dunghaiquyen.ecommerce.modules.order.dto.UpdateOrderStatusRequest;
import com.dunghaiquyen.ecommerce.modules.order.dto.CancelOrderRequest;
import com.dunghaiquyen.ecommerce.modules.order.service.OrderService;
import com.dunghaiquyen.ecommerce.modules.returns.dto.RefundResponse;
import com.dunghaiquyen.ecommerce.modules.returns.service.ReturnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API_SPEC_PHASE1.md section 10 - admin/sales order management. */
@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF')")
public class AdminOrderController {

    private final OrderService orderService;
    private final ReturnService returnService;

    public AdminOrderController(OrderService orderService, ReturnService returnService) {
        this.orderService = orderService;
        this.returnService = returnService;
    }

    @GetMapping
    public ApiResponse<List<AdminOrderResponse>> list(@ModelAttribute AdminOrderListQuery query) {
        OrderService.ListResult<AdminOrderResponse> result = orderService.listOrdersForAdmin(query);
        return ApiResponse.ok(result.items(), result.meta());
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminOrderResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.getOrderDetailForAdmin(id));
    }

    @PostMapping("/packing-slip-batch")
    public ApiResponse<List<AdminOrderResponse>> packingSlipBatch(
            @Valid @RequestBody PackingSlipBatchRequest request) {
        return ApiResponse.ok(orderService.getPackingSlipDetailsForAdmin(request.orderIds()));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminOrderResponse> updateStatus(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        AdminOrderResponse response = orderService.updateOrderStatus(id, request, principal.getUser());
        return ApiResponse.ok("Order status updated", response);
    }

    @GetMapping("/{id}/refunds")
    public ApiResponse<List<RefundResponse>> refunds(@PathVariable UUID id) {
        return ApiResponse.ok(returnService.listOrderRefundsForAdmin(id));
    }

    /** Completes the refund work item created when a paid order was cancelled. */
    @PostMapping("/{id}/cancellation-refund")
    public ApiResponse<Object> refundCancellation(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            HttpServletRequest request) {
        String ipAddress = request.getRemoteAddr();
        RefundResponse response = returnService.refundCancellation(
                id, "Refund for cancelled order", principal.getUser(), ipAddress);
        return ApiResponse.ok("Cancellation refund completed", response);
    }

    /** Cancels immediately; a paid order gets a separate pending refund. */
    @PostMapping("/{id}/cancel")
    public ApiResponse<AdminOrderResponse> cancelByStaff(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            @RequestBody(required = false) CancelOrderRequest request) {
        String reason = request == null ? null : request.reason();
        AdminOrderResponse response = returnService.cancelOrderByStaff(id, reason, principal.getUser());
        return ApiResponse.ok(response.paymentStatus() == com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus.PAID
                ? "Order cancelled by staff; refund is pending"
                : "Order cancelled by staff", response);
    }

}
