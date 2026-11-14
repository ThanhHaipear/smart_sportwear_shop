package com.dunghaiquyen.identity.api;

import com.dunghaiquyen.identity.domain.IdentityModels.Address;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import com.dunghaiquyen.identity.persistence.IdentityRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class InternalIdentityApi {
    private final IdentityRepository repository;
    private final byte[] expectedSalesToken;
    public InternalIdentityApi(IdentityRepository repository,@Value("${app.sales-token}")String token){
        this.repository=repository;this.expectedSalesToken=token.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/users/{userId}/addresses/{addressId}")
    public Address address(@RequestHeader("X-Internal-Service-Token")String token,@PathVariable UUID userId,@PathVariable UUID addressId){
        authorize(token);
        return repository.addressForUser(userId,addressId);
    }
    public record UserSummary(UUID id,String fullName,String email,String phone){}
    @GetMapping("/users/{userId}")
    public UserSummary user(@RequestHeader("X-Internal-Service-Token")String token,@PathVariable UUID userId){
        authorize(token);
        User user=repository.userById(userId).orElseThrow(()->new IllegalArgumentException("User not found"));
        return new UserSummary(user.id(),user.fullName(),user.email(),user.phone());
    }
    private void authorize(String token){
        if(!MessageDigest.isEqual(expectedSalesToken,token.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("Invalid service token");
    }
}
