package com.dunghaiquyen.sales.api;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import com.dunghaiquyen.sales.config.SalesProperties;
import com.dunghaiquyen.sales.domain.SalesDomainService;
import com.dunghaiquyen.sales.domain.CheckoutApplicationService;
import com.dunghaiquyen.sales.identity.IdentityClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SalesApi {
    private final SalesDomainService service; private final CheckoutApplicationService checkout; private final IdentityClient identity; private final SePaySignatureVerifier signatures; private final SalesProperties properties; private final ObjectMapper json; private final Validator validator;
    public SalesApi(SalesDomainService service,CheckoutApplicationService checkout,IdentityClient identity,SePaySignatureVerifier signatures,SalesProperties properties,ObjectMapper json,Validator validator){this.service=service;this.checkout=checkout;this.identity=identity;this.signatures=signatures;this.properties=properties;this.json=json;this.validator=validator;}
    public record StorefrontOrderRequest(UUID userId,List<OrderLineRequest> items,PaymentMethod paymentMethod,Map<String,Object> address,
            BigDecimal shippingFee,String note,UUID addressId,List<UUID> cartItemIds,UUID buyNowVariantId,Integer buyNowQuantity){}
    @PostMapping("/api/v1/orders") public ResponseEntity<ApiResponse<OrderResponse>> order(@RequestHeader("X-User-Id") UUID user,@RequestBody StorefrontOrderRequest request){
        if(request.userId()!=null&&!user.equals(request.userId()))throw new SecurityException("Order user does not match authenticated user");
        List<OrderLineRequest> lines=request.items()!=null&&!request.items().isEmpty()?request.items():checkout.lines(user,request.cartItemIds(),request.buyNowVariantId(),request.buyNowQuantity());
        if(lines.isEmpty())throw new IllegalArgumentException("Cart is empty");
        Map<String,Object> address=request.address()!=null?request.address():identity.address(user,Objects.requireNonNull(request.addressId(),"addressId is required"));
        CreateOrderRequest command=new CreateOrderRequest(user,lines,Objects.requireNonNull(request.paymentMethod(),"paymentMethod is required"),address,request.shippingFee(),request.note());
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createOrder(command)));
    }
    @PostMapping(path="/api/v1/webhooks/sepay",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> sepay(@RequestHeader("X-SePay-Signature")String signature,@RequestHeader("X-SePay-Timestamp")String timestamp,@RequestBody byte[] rawBody)throws Exception{
        if(!properties.sepay().webhookEnabled())return ResponseEntity.status(503).body(Map.of("success",false,"error","SEPAY_DISABLED"));
        signatures.verify(signature,timestamp,rawBody); SePayRequest request=json.readValue(rawBody,SePayRequest.class);
        if(!validator.validate(request).isEmpty())throw new IllegalArgumentException("Invalid SePay webhook payload");
        IngestionResult result=service.ingest(request);return ResponseEntity.status(result.alreadyProcessed()?200:201).body(Map.of("success",true,"alreadyProcessed",result.alreadyProcessed(),"matchStatus",result.matchStatus()));
    }
    @PostMapping("/internal/v1/refunds") public ResponseEntity<RefundResponse> refund(@RequestHeader("X-Internal-Service-Token")String token,@RequestHeader("X-Actor-Id")UUID actor,@Valid @RequestBody CreateRefundRequest request){
        if(!MessageDigestSupport.equals(token,properties.internalToken()))throw new SecurityException("Invalid internal service token");
        return ResponseEntity.status(201).body(service.createRefund(request,actor));
    }
}
