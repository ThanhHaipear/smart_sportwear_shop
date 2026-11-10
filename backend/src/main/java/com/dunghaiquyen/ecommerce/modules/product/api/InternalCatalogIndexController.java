package com.dunghaiquyen.ecommerce.modules.product.api;

import com.dunghaiquyen.ecommerce.modules.product.service.CatalogIndexDocumentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalCatalogIndexController {
    private final CatalogIndexDocumentService service;

    @Value("${app.search-service.internal-token}")
    private String expectedToken;

    @GetMapping("/internal/v1/products/{productId}/index-document")
    public ResponseEntity<CatalogIndexDocumentResponse> get(
            @PathVariable UUID productId,
            @RequestHeader(value = "X-Internal-Service-Token", defaultValue = "") String token) {
        if (expectedToken.isBlank() || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        CatalogIndexDocumentResponse document = service.find(productId);
        return document == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(document);
    }
}
