package com.dunghaiquyen.ecommerce.modules.payment.controller;

import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import com.dunghaiquyen.ecommerce.common.security.CustomUserDetails;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.dto.PaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.service.PaymentService;
import com.dunghaiquyen.ecommerce.modules.payment.service.VietQrPaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment APIs for VietQR creation and provider-neutral payment history. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final VietQrPaymentService vietQrPaymentService;

    public PaymentController(
            PaymentService paymentService, VietQrPaymentService vietQrPaymentService) {
        this.paymentService = paymentService;
        this.vietQrPaymentService = vietQrPaymentService;
    }

    @PostMapping("/vietqr")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CreateVietQrPaymentResponse>> createVietQr(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody CreateVietQrPaymentRequest request) {
        CreateVietQrPaymentResponse response =
                vietQrPaymentService.createOrResume(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("VietQR payment ready", response));
    }

    @GetMapping("/vietqr/{paymentId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<CreateVietQrPaymentResponse> getVietQr(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID paymentId) {
        return ApiResponse.ok(vietQrPaymentService.getPayment(principal.getUserId(), paymentId));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SALES_STAFF')")
    public ApiResponse<List<PaymentResponse>> getByOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID orderId) {
        List<PaymentResponse> response =
                paymentService.getPaymentsByOrder(orderId, principal.getUserId(), principal.getUser().getRole());
        return ApiResponse.ok(response);
    }
}
