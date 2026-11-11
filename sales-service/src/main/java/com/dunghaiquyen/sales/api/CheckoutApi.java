package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.domain.CheckoutApplicationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutApi {
    private final CheckoutApplicationService checkout;
    public CheckoutApi(CheckoutApplicationService checkout){this.checkout=checkout;}
    public record PreviewRequest(UUID addressId,List<UUID> cartItemIds,UUID buyNowVariantId,Integer buyNowQuantity){}
    @PostMapping("/preview") ApiResponse<Map<String,Object>> preview(@RequestHeader("X-User-Id")UUID user,
            @RequestBody(required=false)PreviewRequest request){
        PreviewRequest value=request==null?new PreviewRequest(null,null,null,null):request;
        return ApiResponse.ok(checkout.preview(user,value.cartItemIds(),value.buyNowVariantId(),value.buyNowQuantity()));
    }
}
