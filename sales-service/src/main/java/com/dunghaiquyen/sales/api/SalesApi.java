package com.dunghaiquyen.sales.api;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import com.dunghaiquyen.sales.config.SalesProperties;
import com.dunghaiquyen.sales.domain.SalesDomainService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SalesApi {
    private final SalesDomainService service; private final SePaySignatureVerifier signatures; private final SalesProperties properties; private final ObjectMapper json; private final Validator validator;
    public SalesApi(SalesDomainService service,SePaySignatureVerifier signatures,SalesProperties properties,ObjectMapper json,Validator validator){this.service=service;this.signatures=signatures;this.properties=properties;this.json=json;this.validator=validator;}
    @PostMapping("/api/v1/orders") public ResponseEntity<OrderResponse> order(@Valid @RequestBody CreateOrderRequest request){return ResponseEntity.status(201).body(service.createOrder(request));}
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
