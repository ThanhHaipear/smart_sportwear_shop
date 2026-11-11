package com.dunghaiquyen.catalog.api;

import com.dunghaiquyen.catalog.persistence.CatalogReviewRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1")
public class CatalogReviewApi {
    private final CatalogReviewRepository repository; private final RestClient sales; private final String token;
    public CatalogReviewApi(CatalogReviewRepository repository,RestClient.Builder builder,@Value("${app.sales-base-url:http://localhost:8094}")String salesBase,@Value("${app.sales-token:change-me}")String token){this.repository=repository;this.sales=builder.baseUrl(salesBase).build();this.token=token;}
    public record CreateReview(Integer rating,String title,String content,UUID orderItemId){}
    public record ReviewStatus(String status){}
    @GetMapping("/products/{product}/reviews") public ApiResponse<List<Map<String,Object>>> publicReviews(@PathVariable String product,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit){UUID id=repository.productId(product);int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.count(id,"APPROVED");return ApiResponse.ok(repository.list(id,"APPROVED",l,(p-1)*l),meta(p,l,total));}
    @PostMapping("/products/{product}/reviews") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Map<String,Object>> create(@RequestHeader("X-User-Id")UUID user,@PathVariable String product,@RequestBody CreateReview body){UUID id=repository.productId(product);Map<?,?> eligibility=sales.get().uri(uri->uri.path("/internal/v1/reviews/eligibility").queryParam("userId",user).queryParam("productId",id).queryParamIfPresent("orderItemId",java.util.Optional.ofNullable(body.orderItemId())).build()).header("X-Internal-Service-Token",token).retrieve().body(Map.class);if(eligibility==null||!Boolean.TRUE.equals(eligibility.get("eligible")))throw new IllegalStateException("Only delivered purchases can be reviewed");UUID item=UUID.fromString(String.valueOf(eligibility.get("orderItemId")));return ApiResponse.ok(repository.create(id,user,item,body.rating()==null?0:body.rating(),body.title(),body.content()));}
    @GetMapping("/admin/reviews") public ApiResponse<List<Map<String,Object>>> adminReviews(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String status){int p=Math.max(1,page),l=Math.max(1,Math.min(limit,100));long total=repository.count(null,status);return ApiResponse.ok(repository.list(null,status,l,(p-1)*l),meta(p,l,total));}
    @GetMapping("/admin/reviews/{id}") public ApiResponse<Map<String,Object>> review(@PathVariable UUID id){return ApiResponse.ok(repository.detail(id));}
    @PatchMapping("/admin/reviews/{id}/status") public ApiResponse<Map<String,Object>> reviewStatus(@PathVariable UUID id,@RequestBody ReviewStatus body){return ApiResponse.ok(repository.status(id,body.status()));}
    private Map<String,Object> meta(int page,int limit,long total){return Map.of("page",page,"limit",limit,"total",total,"totalPages",total==0?0:(total+limit-1)/limit);}
}
