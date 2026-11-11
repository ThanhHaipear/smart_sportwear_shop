package com.dunghaiquyen.sales.identity;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class IdentityClient {
    private final RestClient client;
    public IdentityClient(RestClient.Builder builder,@Value("${sales.identity-base-url}")String baseUrl,
                          @Value("${sales.identity-token}")String token){
        this.client=builder.baseUrl(baseUrl).defaultHeader("X-Internal-Service-Token",token).build();
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> address(UUID userId,UUID addressId){
        return client.get().uri("/internal/v1/users/{userId}/addresses/{addressId}",userId,addressId)
                .retrieve().body(Map.class);
    }
}
