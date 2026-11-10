package com.dunghaiquyen.ecommerce.visualsearch.api;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;

@Component
public class VisualSearchClient {

    private static final Logger log = LoggerFactory.getLogger(VisualSearchClient.class);

    private final VisualSearchProperties properties;
    private final RestClient restClient;

    public VisualSearchClient(VisualSearchProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.requestTimeoutSeconds()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(properties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<VisualSearchCandidate> search(MultipartFile image, int limit) {
        try {
            ByteArrayResource imageResource = new ByteArrayResource(image.getBytes()) {
                @Override
                public String getFilename() {
                    return image.getOriginalFilename() == null ? "query-image" : image.getOriginalFilename();
                }
            };
            MediaType imageType = image.getContentType() == null || image.getContentType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(image.getContentType());
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("image", imageResource)
                    .filename(imageResource.getFilename())
                    .contentType(imageType);
            VisualSearchInternalResponse response = restClient.post()
                    .uri(uri -> uri.path("/internal/v1/search/image").queryParam("limit", limit).build())
                    .header("X-Internal-Service-Token", properties.internalToken())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(VisualSearchInternalResponse.class);
            return response == null || response.candidates() == null ? List.of() : response.candidates();
        } catch (RestClientResponseException ex) {
            log.warn("Visual search service returned HTTP {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            HttpStatus status = switch (ex.getStatusCode().value()) {
                case 400, 413, 415, 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new BusinessRuleException(status, "Visual search request failed");
        } catch (InvalidMediaTypeException ex) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid image MIME type");
        } catch (ResourceAccessException ex) {
            log.warn("Visual search service could not be reached before the configured timeout");
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search request timed out");
        } catch (RestClientException | java.io.IOException ex) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is temporarily unavailable");
        }
    }

    public boolean isReady() {
        if (!properties.enabled()) return false;
        try {
            restClient.get().uri("/health/ready").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            return false;
        }
    }

    public <T> T getAdmin(String path, Class<T> responseType) {
        return executeAdmin(path, responseType, null, false);
    }

    public <T> T postAdmin(String path, Class<T> responseType) {
        return executeAdmin(path, responseType, null, true);
    }

    public <T> T postAdmin(String path, Object body, Class<T> responseType) {
        return executeAdmin(path, responseType, body, true);
    }

    private <T> T executeAdmin(String path, Class<T> responseType, Object body, boolean post) {
        try {
            RestClient.RequestHeadersSpec<?> request;
            if (body != null) {
                ObjectMapper mapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
                request = restClient.post().uri(path)
                        .header("X-Internal-Service-Token", properties.internalToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(body));
            } else if (post) {
                request = restClient.post().uri(path).header("X-Internal-Service-Token", properties.internalToken());
            } else {
                request = restClient.get().uri(path).header("X-Internal-Service-Token", properties.internalToken());
            }
            T response = request.retrieve().body(responseType);
            if (response == null) {
                throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search returned an empty response");
            }
            return response;
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is temporarily unavailable");
        }
    }
}
