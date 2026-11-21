package com.dunghaiquyen.catalog.api;

import com.dunghaiquyen.catalog.persistence.PublicCatalogRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class VisualSearchApi {
    private static final int MAX_BYTES = 5_242_880;
    private static final int CANDIDATE_LIMIT = 20;
    private static final String SEARCH_PATH = "/internal/v1/search/image";
    private static final String GATEWAY_HEADER = "X-Visual-Search-Gateway-Token";

    private final PublicCatalogRepository repository;
    private final RestClient search;
    private final boolean enabled;
    private final String gatewayToken;

    public VisualSearchApi(
            PublicCatalogRepository repository, RestClient.Builder builder,
            @Value("${app.visual-search.enabled:false}") boolean enabled,
            @Value("${app.visual-search.search-base-url:http://localhost:8090}") String baseUrl,
            @Value("${app.visual-search.search-token:}") String token,
            @Value("${app.visual-search.gateway-token:}") String gatewayToken) {
        this.repository = repository;
        this.enabled = enabled;
        this.gatewayToken = gatewayToken;
        if (enabled && token.isBlank()) {
            throw new IllegalArgumentException("SEARCH_SERVICE_INTERNAL_TOKEN is required for public visual search");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(7));
        this.search = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Internal-Service-Token", token).build();
    }

    @GetMapping("/visual-search/status")
    public ApiResponse<Map<String, Object>> status(@RequestHeader(name = GATEWAY_HEADER, defaultValue = "") String gateway) {
        requireGateway(gateway);
        boolean available = false;
        if (enabled) {
            try {
                var readiness = search.get().uri(SEARCH_PATH + "/status").retrieve().body(SearchReadiness.class);
                available = readiness != null && readiness.available();
            } catch (RestClientException ignored) {
                // Storefront can still load when Search is temporarily unavailable.
            }
        }
        return ApiResponse.ok(Map.of("enabled", enabled, "available", available,
                "status", available ? "READY" : enabled ? "UNAVAILABLE" : "DISABLED"));
    }

    @PostMapping("/products/search-by-image")
    public ApiResponse<List<VisualResult>> find(
            @RequestParam(required = false) MultipartFile image, @RequestParam(defaultValue = "4") int limit,
            @RequestHeader(name = GATEWAY_HEADER, defaultValue = "") String gateway)
            throws IOException {
        requireGateway(gateway);
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is disabled");
        }
        if (image == null || image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Image is required");
        }
        if (image.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the upload limit");
        }
        int resultLimit = Math.clamp(limit, 1, CANDIDATE_LIMIT);
        MediaType mediaType;
        try {
            mediaType = image.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(image.getContentType());
        } catch (InvalidMediaTypeException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid image content type", error);
        }
        var partHeaders = new HttpHeaders();
        partHeaders.setContentType(mediaType);
        partHeaders.setContentDispositionFormData("image", "query");
        var data = new LinkedMultiValueMap<String, Object>();
        data.add("image", new HttpEntity<>(new ByteArrayResource(image.getBytes()), partHeaders));
        SearchResponse response;
        try {
            response = search.post().uri(SEARCH_PATH + "?limit={limit}&pilot_v4=true", CANDIDATE_LIMIT)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(data).retrieve()
                    .onStatus(code -> code.value() == 422, (request, upstream) -> {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported or invalid image");
                    })
                    .onStatus(HttpStatusCode::isError, (request, upstream) -> {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is temporarily unavailable");
                    })
                    .body(SearchResponse.class);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is temporarily unavailable", error);
        }
        if (response == null || response.candidates() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is temporarily unavailable");
        }
        return ApiResponse.ok(hydrate(response.candidates(), resultLimit));
    }

    private void requireGateway(String supplied) {
        if (!gatewayToken.isEmpty() && !MessageDigest.isEqual(
                gatewayToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Visual search requires the Gateway");
        }
    }

    private List<VisualResult> hydrate(List<Candidate> candidates, int limit) {
        var cards = repository.visualSearchProducts(candidates.stream().map(Candidate::productId).distinct().toList());
        var images = repository.visualSearchImages(candidates.stream().map(Candidate::imageId).distinct().toList());
        var seen = new HashSet<UUID>();
        var results = new ArrayList<VisualResult>();
        for (Candidate candidate : candidates) {
            var card = cards.get(candidate.productId());
            var image = images.get(candidate.imageId());
            if (card != null && image != null && candidate.productId().equals(image.productId())
                    && image.url() != null && seen.add(candidate.productId())) {
                results.add(new VisualResult(card, candidate.imageId(), image.url(), candidate.similarity()));
            }
        }
        results.sort(Comparator.comparing(result -> ((Number) result.product().get("availableQuantity")).intValue() <= 0));
        return results.stream().limit(limit).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(@JsonProperty("product_id") UUID productId, @JsonProperty("image_id") UUID imageId,
                     double similarity) {}

    record SearchReadiness(boolean available) {}
    record SearchResponse(List<Candidate> candidates) {}
    public record VisualResult(Map<String, Object> product, UUID matchedImageId, String matchedImageUrl,
                               double similarity) {}
}
