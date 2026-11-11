package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.persistence.SalesWorkflowRepository;
import com.dunghaiquyen.sales.persistence.SalesWorkflowRepository.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SalesWorkflowApi {
    private final SalesWorkflowRepository repository;
    public SalesWorkflowApi(SalesWorkflowRepository repository){this.repository=repository;}
    public record StatusRequest(String status,List<Resolution> items){}
    public record RefundRequest(String reason){}
    public record ManualConfirmation(String reference,String note){}

    @PostMapping("/orders/{id}/cancel") public ApiResponse<Map<String,Object>> cancel(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id,@RequestBody(required=false)CancelRequest body){return ApiResponse.ok(repository.cancelOrder(id,user,body));}
    @PostMapping("/admin/orders/{id}/cancel") public ApiResponse<Map<String,Object>> adminCancel(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody(required=false)CancelRequest body){return ApiResponse.ok(repository.cancelOrderByStaff(id,actor,body));}
    @PutMapping("/orders/{id}/refund-destination") public ApiResponse<Map<String,Object>> destination(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id,@RequestBody RefundDestination body){return ApiResponse.ok(repository.updateRefundDestination(id,user,body));}
    @PostMapping("/returns") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Map<String,Object>> createReturn(@RequestHeader("X-User-Id")UUID user,@RequestBody CreateReturnRequest body){return ApiResponse.ok(repository.createReturn(user,body));}
    @GetMapping("/returns/me") public ApiResponse<List<Map<String,Object>>> myReturns(@RequestHeader("X-User-Id")UUID user){return ApiResponse.ok(repository.myReturns(user));}
    @GetMapping("/returns/{id}") public ApiResponse<Map<String,Object>> returnDetail(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id){return ApiResponse.ok(repository.returnDetail(id,user,false));}
    @PostMapping("/returns/{id}/cancel") public ApiResponse<Map<String,Object>> cancelReturn(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id){return ApiResponse.ok(repository.cancelReturn(id,user));}

    @PatchMapping("/admin/returns/{id}/status") public ApiResponse<Map<String,Object>> returnStatus(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody StatusRequest body){return ApiResponse.ok(repository.updateReturnStatus(id,body.status(),body.items(),actor));}
    @GetMapping("/admin/returns/{id}/refunds") public ApiResponse<List<Map<String,Object>>> returnRefunds(@PathVariable UUID id){return ApiResponse.ok(repository.refundsForReturn(id));}
    @PostMapping("/admin/returns/{id}/refund") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Map<String,Object>> refund(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody(required=false)RefundRequest body){return ApiResponse.ok(repository.createRefundForReturn(id,body==null?null:body.reason(),actor));}
    @PatchMapping("/admin/refunds/{id}/status") public ApiResponse<Map<String,Object>> refundStatus(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody StatusRequest body){return ApiResponse.ok(repository.updateRefundStatus(id,body.status(),actor));}
    @PostMapping("/admin/refunds/{id}/manual-confirmation") public ApiResponse<Map<String,Object>> manual(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody ManualConfirmation body){return ApiResponse.ok(repository.confirmManualRefund(id,body.reference(),body.note(),actor));}
}
