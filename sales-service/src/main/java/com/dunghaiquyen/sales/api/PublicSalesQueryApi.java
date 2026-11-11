package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.persistence.SalesQueryRepository;
import com.dunghaiquyen.sales.config.SalesProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class PublicSalesQueryApi {
    private final SalesQueryRepository repository; private final SalesProperties properties; private final String bankBin; private final String accountName; private final NotificationStreamService streams;
    public PublicSalesQueryApi(SalesQueryRepository repository,SalesProperties properties,
            @Value("${sales.vietqr.bank-bin:}")String bankBin,@Value("${sales.vietqr.account-name:}")String accountName,NotificationStreamService streams){this.repository=repository;this.properties=properties;this.bankBin=bankBin;this.accountName=accountName;this.streams=streams;}
    public record VietQrRequest(UUID orderId){}

    @GetMapping("/orders/me")
    ApiResponse<List<Map<String,Object>>> orders(@RequestHeader("X-User-Id") UUID user,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){
        int safePage=Math.max(1,page),safeLimit=Math.max(1,Math.min(limit,100));long total=repository.orderCount(user);
        return ApiResponse.ok(repository.orders(user,safeLimit,(safePage-1)*safeLimit),Map.of(
                "page",safePage,"limit",safeLimit,"total",total,"totalPages",total==0?0:(total+safeLimit-1)/safeLimit));
    }

    @GetMapping("/orders/{id}")
    ApiResponse<Map<String,Object>> order(@RequestHeader("X-User-Id") UUID user,
            @RequestHeader(value="X-User-Role",defaultValue="CUSTOMER") String role,@PathVariable UUID id){
        boolean staff=role.equals("ADMIN")||role.equals("SALES_STAFF");
        return ApiResponse.ok(repository.order(user,id,staff).orElseThrow(()->new IllegalArgumentException("Order not found")));
    }

    @GetMapping("/orders/{id}/refunds")
    ApiResponse<List<Map<String,Object>>> refunds(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id){return ApiResponse.ok(repository.refunds(user,id));}
    @GetMapping("/orders/{id}/invoice") ApiResponse<Map<String,Object>> invoice(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id){return ApiResponse.ok(repository.invoice(user,id));}

    @GetMapping("/payments/{orderId}")
    ApiResponse<List<Map<String,Object>>> payments(@RequestHeader("X-User-Id")UUID user,
            @RequestHeader(value="X-User-Role",defaultValue="CUSTOMER")String role,@PathVariable UUID orderId){
        return ApiResponse.ok(repository.payments(user,orderId,role.equals("ADMIN")||role.equals("SALES_STAFF")));}

    @GetMapping("/payments/vietqr/{paymentId}")
    ApiResponse<Map<String,Object>> vietQr(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID paymentId){
        return ApiResponse.ok(repository.vietQr(user,paymentId).orElseThrow(()->new IllegalArgumentException("Payment not found")));}

    @PostMapping("/payments/vietqr")
    ApiResponse<Map<String,Object>> createVietQr(@RequestHeader("X-User-Id")UUID user,@RequestBody VietQrRequest request){
        return ApiResponse.ok(repository.vietQrByOrder(user,request.orderId(),properties.vietqr().accountNumber(),bankBin,accountName)
                .orElseThrow(()->new IllegalArgumentException("VietQR payment not found")));}

    @GetMapping("/notifications/me")
    ApiResponse<List<Map<String,Object>>> notifications(@RequestHeader("X-User-Id")UUID user,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){
        int safePage=Math.max(1,page),safeLimit=Math.max(1,Math.min(limit,100));long total=repository.notificationCount(user);
        return ApiResponse.ok(repository.notifications(user,safeLimit,(safePage-1)*safeLimit),Map.of(
                "page",safePage,"limit",safeLimit,"total",total,"totalPages",total==0?0:(total+safeLimit-1)/safeLimit));}

    @GetMapping("/notifications/me/unread-count") ApiResponse<Map<String,Long>> unread(@RequestHeader("X-User-Id")UUID user){return ApiResponse.ok(Map.of("unread",repository.unread(user)));}
    @PostMapping("/notifications/me/{id}/read") ApiResponse<Void> read(@RequestHeader("X-User-Id")UUID user,@PathVariable UUID id){repository.markRead(user,id);streams.changed(user,"notification-read");return ApiResponse.ok(null);}
    @PostMapping("/notifications/me/read-all") ApiResponse<Integer> readAll(@RequestHeader("X-User-Id")UUID user){int changed=repository.markAllRead(user);streams.changed(user,"notifications-read");return ApiResponse.ok(changed);}
    @GetMapping(value="/notifications/me/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter stream(@RequestHeader("X-User-Id")UUID user){return streams.register(user);}
    @GetMapping("/settings/public") ApiResponse<Map<String,Object>> settings(){return ApiResponse.ok(repository.publicSettings());}
}
