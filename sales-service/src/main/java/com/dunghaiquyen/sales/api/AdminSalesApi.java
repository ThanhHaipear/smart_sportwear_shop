package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.persistence.SalesAdminRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSalesApi {
    private final SalesAdminRepository repository;
    public AdminSalesApi(SalesAdminRepository repository){this.repository=repository;}
    public enum RevenueGranularity {DAY,MONTH,YEAR}
    public record StatusBody(String status,String reason){} public record SettingBody(Object value,String type,String description,Boolean isPublic){} public record OrderIds(List<UUID> orderIds){}
    @GetMapping("/orders") ApiResponse<List<Map<String,Object>>> orders(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit,
            @RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)UUID customerId,
            @RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo,@RequestParam(required=false)UUID productId){
        dates(dateFrom,dateTo);int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.orderCount(keyword,status,customerId,dateFrom,dateTo,productId);return ApiResponse.ok(repository.orders(l,(p-1)*l,keyword,status,customerId,dateFrom,dateTo,productId),meta(p,l,total));}
    @GetMapping("/orders/{id}") ApiResponse<Map<String,Object>> order(@PathVariable UUID id){return ApiResponse.ok(repository.order(id));}
    @PostMapping("/orders/packing-slip-batch") ApiResponse<List<Map<String,Object>>> packingSlips(@RequestBody OrderIds body){return ApiResponse.ok(body.orderIds()==null?List.of():body.orderIds().stream().distinct().map(repository::order).toList());}
    @PatchMapping("/orders/{id}/status") ApiResponse<Map<String,Object>> status(@RequestHeader("X-User-Id")UUID actor,@PathVariable UUID id,@RequestBody StatusBody body){return ApiResponse.ok(repository.status(id,body.status(),body.reason(),actor));}
    @GetMapping("/orders/{id}/refunds") ApiResponse<List<Map<String,Object>>> refunds(@PathVariable UUID id){return ApiResponse.ok(repository.refunds(id));}
    @GetMapping("/reports/overview") ApiResponse<Map<String,Object>> overview(){return ApiResponse.ok(repository.overview());}
    @GetMapping("/reports/orders") ApiResponse<Map<String,Object>> orderReport(){return ApiResponse.ok(repository.orderReport());}
    @GetMapping("/reports/revenue") ApiResponse<Map<String,Object>> revenue(
            @RequestParam(defaultValue="MONTH") RevenueGranularity granularity,
            @RequestParam(required=false) LocalDate dateFrom,
            @RequestParam(required=false) LocalDate dateTo){
        return ApiResponse.ok(repository.revenueReport(granularity.name(),dateFrom,dateTo));
    }
    @GetMapping("/reports/products") ApiResponse<Map<String,Object>> products(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo){dates(dateFrom,dateTo);int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));return ApiResponse.ok(repository.productReport(p,l,dateFrom,dateTo));}
    @GetMapping("/reports/customers") ApiResponse<Map<String,Object>> customers(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo){dates(dateFrom,dateTo);int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));return ApiResponse.ok(repository.customerReport(p,l,dateFrom,dateTo));}
    @GetMapping("/reports/inventory") ApiResponse<Map<String,Object>> inventory(){return ApiResponse.ok(repository.inventoryReport());}
    @GetMapping("/returns") ApiResponse<List<Map<String,Object>>> returns(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String status){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));String filter=status==null||status.isBlank()?null:status;long total=repository.returnCount(filter);return ApiResponse.ok(repository.returns(l,(p-1)*l,filter),meta(p,l,total));}
    @GetMapping("/returns/{id}") ApiResponse<Map<String,Object>> returnDetail(@PathVariable UUID id){return ApiResponse.ok(repository.returnDetail(id));}
    @GetMapping("/settings") ApiResponse<List<Map<String,Object>>> settings(){return ApiResponse.ok(repository.settings());}
    @PutMapping("/settings/{key}") ApiResponse<Map<String,Object>> setting(@RequestHeader("X-User-Id")UUID actor,@PathVariable String key,@RequestBody SettingBody body){return ApiResponse.ok(repository.setting(key,body.value(),body.type(),body.description(),Boolean.TRUE.equals(body.isPublic()),actor));}
    @GetMapping("/audit-logs") ApiResponse<List<Map<String,Object>>> audits(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.auditCount();return ApiResponse.ok(repository.audits(l,(p-1)*l),meta(p,l,total));}
    private Map<String,Object> meta(int page,int limit,long total){return Map.of("page",page,"limit",limit,"total",total,"totalPages",total==0?0:(total+limit-1)/limit);}
    private void dates(LocalDate from,LocalDate to){if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("dateFrom must not be after dateTo");}
}
