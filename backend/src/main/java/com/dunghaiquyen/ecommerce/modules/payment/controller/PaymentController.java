package com.dunghaiquyen.ecommerce.modules.payment.controller;

import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import com.dunghaiquyen.ecommerce.common.security.CustomUserDetails;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreatePaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreatePaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.dto.PaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.service.PaymentService;
import com.dunghaiquyen.ecommerce.modules.payment.service.VietQrPaymentService;
import com.dunghaiquyen.ecommerce.modules.payment.service.VnpayTransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API_SPEC_PHASE1.md section 8 - VNPay 2.1.0 payment session and callback. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final VnpayTransactionService transactionService;
    private final VietQrPaymentService vietQrPaymentService;

    public PaymentController(
            PaymentService paymentService,
            VnpayTransactionService transactionService,
            VietQrPaymentService vietQrPaymentService) {
        this.paymentService = paymentService;
        this.transactionService = transactionService;
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

    @PostMapping("/create")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CreatePaymentResponse>> create(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        CreatePaymentResponse response =
                paymentService.createPaymentSession(principal.getUserId(), request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Payment session created", response));
    }

    /**
     * Public per API_SPEC_PHASE1.md 8.2 (already permitted in SecurityConfig's
     * PUBLIC_ANY_METHOD_ENDPOINTS) - the gateway calls this directly with no JWT,
     * auth here is the checksum verified inside PaymentService. Bound as a flat
     * @RequestParam map rather than a typed DTO: VNPay's payload is a flat
     * vnp_* key/value set (not JSON), and the handler needs to hash over
     * whatever set of params actually arrived, not a fixed shape.
     */
    @RequestMapping(value = "/callback", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, String> callback(@RequestParam Map<String, String> params) {
        return paymentService.handleCallback(params);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SALES_STAFF')")
    public ApiResponse<List<PaymentResponse>> getByOrder(
            @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID orderId) {
        List<PaymentResponse> response =
                paymentService.getPaymentsByOrder(orderId, principal.getUserId(), principal.getUser().getRole());
        return ApiResponse.ok(response);
    }

    @PostMapping("/{paymentId}/query")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF')")
    public ApiResponse<Map<String, Object>> query(
            @PathVariable UUID paymentId, HttpServletRequest request) {
        return ApiResponse.ok(transactionService.queryPayment(paymentId, request.getRemoteAddr()));
    }
}
