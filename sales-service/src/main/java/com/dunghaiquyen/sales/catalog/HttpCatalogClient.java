package com.dunghaiquyen.sales.catalog;

import com.dunghaiquyen.sales.config.SalesProperties;
import com.dunghaiquyen.sales.domain.SalesModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpCatalogClient implements CatalogClient {
    private final RestClient client;
    public HttpCatalogClient(RestClient.Builder builder, SalesProperties properties) {
        this.client=builder.baseUrl(properties.catalogBaseUrl()).defaultHeader("X-Internal-Service-Token",properties.catalogToken()).build();
    }
    @Override public VariantSnapshot snapshot(UUID id) {
        @SuppressWarnings("unchecked") Map<String,Object> r=client.get().uri("/internal/v1/variants/{id}/sale-snapshot",id).retrieve().body(Map.class);
        Map<String,Object> d=data(r); return new VariantSnapshot(id,uuid(d,"productId"),str(d,"sku"),str(d,"productName"),
                str(d,"size"),str(d,"color"),new BigDecimal(String.valueOf(d.get("unitPrice"))),
                "ACTIVE".equalsIgnoreCase(str(d,"productStatus")) && "ACTIVE".equalsIgnoreCase(str(d,"variantStatus")),
                Integer.parseInt(String.valueOf(d.getOrDefault("availableQuantity",0))));
    }
    @Override public Reservation reserve(UUID orderId,List<OrderLineRequest> items) {
        Map<String,Object> body=Map.of("orderId",orderId,"idempotencyKey","sales-order:"+orderId,
                "items",items.stream().map(i->Map.of("variantId",i.variantId(),"quantity",i.quantity())).toList());
        @SuppressWarnings("unchecked") Map<String,Object> r=client.post().uri("/internal/v1/inventory/reservations").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        Map<String,Object> d=data(r); return new Reservation(uuid(d,"reservationId"),Instant.parse(str(d,"expiresAt")));
    }
    @Override public void confirm(UUID id) { client.post().uri("/internal/v1/inventory/reservations/{id}/confirm",id).retrieve().toBodilessEntity(); }
    @Override public void release(UUID id) { client.delete().uri("/internal/v1/inventory/reservations/{id}",id).retrieve().toBodilessEntity(); }
    @SuppressWarnings("unchecked") private Map<String,Object> data(Map<String,Object> r){Object d=r==null?null:r.get("data");return d instanceof Map? (Map<String,Object>)d:r;}
    private String str(Map<String,Object> m,String k){return Objects.toString(m.get(k),"");}
    private UUID uuid(Map<String,Object> m,String k){return UUID.fromString(str(m,k));}
}
