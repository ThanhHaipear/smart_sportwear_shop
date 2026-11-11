package com.dunghaiquyen.catalog.api;

import com.dunghaiquyen.catalog.persistence.PublicCatalogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogApi {
    private final PublicCatalogRepository repository;
    public AdminCatalogApi(PublicCatalogRepository repository){this.repository=repository;}
    public record StockRequest(UUID requestId,UUID variantId,int quantity,String note,String type){}
    @GetMapping("/products") ApiResponse<List<Map<String,Object>>> products(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){
        int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.adminProductCount();
        return ApiResponse.ok(repository.adminProducts(l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/inventory") ApiResponse<List<Map<String,Object>>> inventory(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="25")int limit,@RequestParam(required=false)String keyword){
        int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.inventoryCount(keyword);
        return ApiResponse.ok(repository.inventory(keyword,l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/inventory/transactions") ApiResponse<List<Map<String,Object>>> transactions(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){
        int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.inventoryTransactionCount();
        return ApiResponse.ok(repository.inventoryTransactions(l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/products/{id}") ApiResponse<Map<String,Object>> product(@PathVariable UUID id){return ApiResponse.ok(repository.adminProduct(id.toString()).orElseThrow(()->new NotFoundException("Product not found")));}
    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> createProduct(@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.createProduct(body));}
    @PatchMapping("/products/{id}") ApiResponse<Map<String,Object>> updateProduct(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.updateProduct(id,body));}
    @DeleteMapping("/products/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteProduct(@PathVariable UUID id){repository.deleteProduct(id);}
    @PostMapping("/products/{id}/variants") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> createVariant(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.createVariant(id,body));}
    @PatchMapping("/variants/{id}") ApiResponse<Map<String,Object>> updateVariant(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.updateVariant(id,body));}
    @PostMapping("/products/{id}/images") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> addImage(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.addImage(id,body));}
    @DeleteMapping("/products/{productId}/images/{imageId}") ApiResponse<List<Map<String,Object>>> deleteImage(@PathVariable UUID productId,@PathVariable UUID imageId){return ApiResponse.ok(repository.deleteImage(productId,imageId));}
    @PatchMapping("/products/{productId}/images/{imageId}/primary") ApiResponse<List<Map<String,Object>>> primaryImage(@PathVariable UUID productId,@PathVariable UUID imageId){return ApiResponse.ok(repository.primaryImage(productId,imageId));}
    @PostMapping("/categories") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> createCategory(@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.createCategory(body));}
    @PatchMapping("/categories/{id}") ApiResponse<Map<String,Object>> updateCategory(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.updateCategory(id,body));}
    @DeleteMapping("/categories/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteCategory(@PathVariable UUID id){repository.deleteCategory(id);}
    @PostMapping("/brands") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> createBrand(@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.createBrand(body));}
    @PatchMapping("/brands/{id}") ApiResponse<Map<String,Object>> updateBrand(@PathVariable UUID id,@RequestBody Map<String,Object> body){return ApiResponse.ok(repository.updateBrand(id,body));}
    @DeleteMapping("/brands/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteBrand(@PathVariable UUID id){repository.deleteBrand(id);}
    @PostMapping("/inventory/import") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> importStock(@RequestHeader("X-User-Id")UUID actor,@RequestBody StockRequest body){return ApiResponse.ok(repository.changeStock(body.requestId(),body.variantId(),body.quantity(),"IMPORT",body.note(),actor));}
    @PostMapping("/inventory/export") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> exportStock(@RequestHeader("X-User-Id")UUID actor,@RequestBody StockRequest body){return ApiResponse.ok(repository.changeStock(body.requestId(),body.variantId(),body.quantity(),"EXPORT",body.note(),actor));}
    @PostMapping("/inventory/adjust") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Map<String,Object>> adjustStock(@RequestHeader("X-User-Id")UUID actor,@RequestBody StockRequest body){return ApiResponse.ok(repository.changeStock(body.requestId(),body.variantId(),body.quantity(),body.type(),body.note(),actor));}
    private Map<String,Object> meta(int page,int limit,long total){return Map.of("page",page,"limit",limit,"total",total,"totalPages",total==0?0:(total+limit-1)/limit);}
}
