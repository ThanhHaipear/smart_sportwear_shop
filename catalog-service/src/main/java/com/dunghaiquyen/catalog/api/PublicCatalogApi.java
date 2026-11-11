package com.dunghaiquyen.catalog.api;

import com.dunghaiquyen.catalog.persistence.PublicCatalogRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublicCatalogApi {
    private final PublicCatalogRepository repository;

    public PublicCatalogApi(PublicCatalogRepository repository) { this.repository = repository; }
    public record WishlistItem(java.util.UUID productId) {}

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories() { return ApiResponse.ok(repository.categories()); }

    @GetMapping("/categories/tree")
    public ApiResponse<List<Map<String, Object>>> categoryTree() {
        List<Map<String, Object>> rows = repository.categories();
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        rows.forEach(row -> {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("children", new java.util.ArrayList<>());
            byId.put(copy.get("id"), copy);
        });
        List<Map<String, Object>> roots = new java.util.ArrayList<>();
        byId.values().forEach(row -> {
            Object parentId = row.get("parentId");
            Map<String, Object> parent = byId.get(parentId);
            if (parent == null) roots.add(row);
            else ((List<Map<String, Object>>) parent.get("children")).add(row);
        });
        return ApiResponse.ok(roots);
    }

    @GetMapping("/categories/{slugOrId}")
    public ApiResponse<Map<String, Object>> category(@PathVariable String slugOrId) {
        return ApiResponse.ok(repository.category(slugOrId).orElseThrow(() -> new NotFoundException("Category not found")));
    }

    @GetMapping("/brands")
    public ApiResponse<List<Map<String, Object>>> brands() { return ApiResponse.ok(repository.brands()); }

    @GetMapping("/promotions/active")
    public ApiResponse<List<Map<String, Object>>> promotions() { return ApiResponse.ok(repository.activePromotions()); }

    @GetMapping("/products/search-suggestions")
    public ApiResponse<List<Map<String, Object>>> suggestions(@RequestParam(required = false) String q) {
        return ApiResponse.ok(repository.suggestions(q));
    }

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categorySlug,
            @RequestParam(required = false) String brandSlug,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String query = q == null || q.isBlank() ? keyword : q;
        long total = repository.productCount(query, categorySlug, brandSlug);
        Map<String, Object> meta = Map.of("page", safePage, "limit", safeLimit, "total", total,
                "totalPages", total == 0 ? 0 : (total + safeLimit - 1) / safeLimit);
        return ApiResponse.ok(repository.products(query, categorySlug, brandSlug, safeLimit,
                (safePage - 1) * safeLimit), meta);
    }

    @GetMapping("/products/hybrid-search")
    public ApiResponse<List<Map<String,Object>>> hybridProducts(@RequestParam(required=false)String q,
            @RequestParam(required=false)String categorySlug,@RequestParam(required=false)String brandSlug,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){
        return products(q,null,categorySlug,brandSlug,page,limit);
    }

    @GetMapping("/visual-search/status")
    public ApiResponse<Map<String,Object>> visualStatus(){return ApiResponse.ok(Map.of("enabled",false,"status","DISABLED"));}
    @PostMapping("/products/search-by-image")
    public void visualSearch(){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Visual search is disabled");}

    @GetMapping("/collections") public ApiResponse<List<Object>> collections(){return ApiResponse.ok(List.of());}
    @GetMapping("/collections/{slug}") public void collection(@PathVariable String slug){throw new NotFoundException("Collection not found");}
    @GetMapping("/banners/active") public ApiResponse<List<Object>> banners(){return ApiResponse.ok(List.of());}
    @GetMapping("/pages/{slug}") public void page(@PathVariable String slug){throw new NotFoundException("Page not found");}

    @GetMapping("/products/{slugOrId}")
    public ApiResponse<Map<String, Object>> product(@PathVariable String slugOrId) {
        return ApiResponse.ok(repository.product(slugOrId).orElseThrow(() -> new NotFoundException("Product not found")));
    }

    @GetMapping("/wishlist")
    public ApiResponse<Map<String,Object>> wishlist(@RequestHeader("X-User-Id")java.util.UUID user){return ApiResponse.ok(repository.wishlist(user));}
    @PostMapping("/wishlist/items")
    public ApiResponse<Map<String,Object>> addWishlist(@RequestHeader("X-User-Id")java.util.UUID user,@RequestBody WishlistItem item){return ApiResponse.ok(repository.addWishlist(user,item.productId()));}
    @DeleteMapping("/wishlist/items/{productId}")
    public ApiResponse<Map<String,Object>> removeWishlist(@RequestHeader("X-User-Id")java.util.UUID user,@PathVariable java.util.UUID productId){return ApiResponse.ok(repository.removeWishlist(user,productId));}
}
