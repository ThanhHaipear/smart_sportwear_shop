package com.dunghaiquyen.ecommerce.modules.returns.controller;

import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import com.dunghaiquyen.ecommerce.common.security.CustomUserDetails;
import com.dunghaiquyen.ecommerce.modules.returns.dto.RefundResponse;
import com.dunghaiquyen.ecommerce.modules.returns.dto.UpdateRefundStatusRequest;
import com.dunghaiquyen.ecommerce.modules.returns.dto.ConfirmManualRefundRequest;
import com.dunghaiquyen.ecommerce.modules.returns.service.ReturnService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

/** Money-movement actions only - ADMIN/SALES_STAFF, no WAREHOUSE_STAFF (see AdminReturnController's javadoc on the same boundary). */
@RestController
@RequestMapping("/api/v1/admin/refunds")
@PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF')")
public class AdminRefundController {

    private final ReturnService returnService;

    public AdminRefundController(ReturnService returnService) {
        this.returnService = returnService;
    }

    /** PENDING -> COMPLETED cascades the linked Return to REFUNDED - see ReturnService.updateRefundStatus's javadoc. */
    @PatchMapping("/{id}/status")
    public ApiResponse<RefundResponse> updateStatus(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRefundStatusRequest request) {
        return ApiResponse.ok("Refund status updated", returnService.updateRefundStatus(id, request, principal.getUser()));
    }

    @PostMapping("/{id}/manual-confirmation")
    public ApiResponse<RefundResponse> confirmManual(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmManualRefundRequest request) {
        return ApiResponse.ok("Manual refund confirmed",
                returnService.confirmManualRefund(id, request, principal.getUser()));
    }
}
