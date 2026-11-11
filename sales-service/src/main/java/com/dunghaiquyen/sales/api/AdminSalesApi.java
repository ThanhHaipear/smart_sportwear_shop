package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.persistence.SalesAdminRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSalesApi {
    private final SalesAdminRepository repository;
    public AdminSalesApi(SalesAdminRepository repository){this.repository=repository;}
    public record StatusBody(String status,String reason){} public record SettingBody(Object value,String type,String description,Boolean isPublic){}
    @GetMapping("/orders") ApiResponse<List<Map<String,Object>>> orders(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.orderCount();return ApiResponse.ok(repository.orders(l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/orders/{id}") ApiResponse<Map<String,Object>> order(@PathVariable UUID id){return ApiResponse.ok(repository.order(id));}
    @PatchMapping("/orders/{id}/status") ApiResponse<Map<String,Object>> status(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody StatusBody body){return ApiResponse.ok(repository.status(id,body.status(),body.reason(),actor));}
    @GetMapping("/orders/{id}/refunds") ApiResponse<List<Map<String,Object>>> refunds(@PathVariable UUID id){return ApiResponse.ok(repository.refunds(id));}
    @GetMapping("/reports/overview") ApiResponse<Map<String,Object>> overview(){return ApiResponse.ok(repository.overview());}
    @GetMapping("/reports/orders") ApiResponse<Map<String,Object>> orderReport(){return ApiResponse.ok(repository.orderReport());}
    @GetMapping("/reports/revenue") ApiResponse<Map<String,Object>> revenue(){return ApiResponse.ok(repository.revenueReport());}
    @GetMapping("/reports/products") ApiResponse<Map<String,Object>> products(@RequestParam(defaultValue="10")int limit){return ApiResponse.ok(repository.productReport(Math.max(1,Math.min(limit,100))));}
    @GetMapping("/reports/customers") ApiResponse<Map<String,Object>> customers(){return ApiResponse.ok(repository.customerReport());}
    @GetMapping("/reports/inventory") ApiResponse<Map<String,Object>> inventory(){return ApiResponse.ok(repository.inventoryReport());}
    @GetMapping("/returns") ApiResponse<List<Map<String,Object>>> returns(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.returnCount();return ApiResponse.ok(repository.returns(l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/returns/{id}") ApiResponse<Map<String,Object>> returnDetail(@PathVariable UUID id){return ApiResponse.ok(repository.returnDetail(id));}
    @PatchMapping("/returns/{id}/status") ApiResponse<Map<String,Object>> returnStatus(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody StatusBody body){return ApiResponse.ok(repository.returnStatus(id,body.status(),actor));}
    @GetMapping("/settings") ApiResponse<List<Map<String,Object>>> settings(){return ApiResponse.ok(repository.settings());}
    @PutMapping("/settings/{key}") ApiResponse<Map<String,Object>> setting(@RequestHeader("X-User-Id")UUID actor,@PathVariable String key,@RequestBody SettingBody body){return ApiResponse.ok(repository.setting(key,body.value(),body.type(),body.description(),Boolean.TRUE.equals(body.isPublic()),actor));}
    @GetMapping("/audit-logs") ApiResponse<List<Map<String,Object>>> audits(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.auditCount();return ApiResponse.ok(repository.audits(l,(p-1)*l),meta(p,l,total));}
    private Map<String,Object> meta(int page,int limit,long total){return Map.of("page",page,"limit",limit,"total",total,"totalPages",total==0?0:(total+limit-1)/limit);}
}
