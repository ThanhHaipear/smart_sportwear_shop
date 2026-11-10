package com.dunghaiquyen.ecommerce.visualsearch.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class VisualSearchClientTest {

    @RegisterExtension
    static WireMockExtension visualSearchStub = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @ParameterizedTest
    @CsvSource({"400,422", "413,422", "415,422", "422,422", "429,429", "503,503", "401,503"})
    void preservesSupportedPublicStatusCodes(int upstreamStatus, int expectedStatus) {
        visualSearchStub.stubFor(post(urlPathEqualTo("/internal/v1/search/image"))
                .willReturn(aResponse().withStatus(upstreamStatus).withBody("{}")));

        BusinessRuleException error = catchThrowableOfType(
                () -> client(2).search(validImage(), 20), BusinessRuleException.class);

        assertThat(error.getStatus().value()).isEqualTo(expectedStatus);
    }

    @Test
    void malformedMimeTypeReturns422InsteadOf500() {
        MockMultipartFile image = new MockMultipartFile(
                "image", "query.bin", "not a valid mime", new byte[]{1});

        BusinessRuleException error = catchThrowableOfType(
                () -> client(2).search(image, 20), BusinessRuleException.class);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void upstreamTimeoutReturns503() {
        visualSearchStub.stubFor(post(urlPathEqualTo("/internal/v1/search/image"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1_250).withBody("{\"candidates\":[]}")));

        BusinessRuleException error = catchThrowableOfType(
                () -> client(1).search(validImage(), 20), BusinessRuleException.class);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void upstreamAuthenticationDetailsAreMappedToGeneric503() {
        visualSearchStub.stubFor(post(urlPathEqualTo("/internal/v1/search/image"))
                .willReturn(aResponse().withStatus(401).withBody("internal-token=must-not-leak")));

        BusinessRuleException error = catchThrowableOfType(
                () -> client(2).search(validImage(), 20), BusinessRuleException.class);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.getMessage()).isEqualTo("Visual search request failed");
        assertThat(error.getMessage()).doesNotContain("must-not-leak");
    }

    private static VisualSearchClient client(int timeoutSeconds) {
        return new VisualSearchClient(new VisualSearchProperties(
                true, visualSearchStub.baseUrl(), "test-token", timeoutSeconds, 10));
    }

    private static MockMultipartFile validImage() {
        return new MockMultipartFile("image", "query.png", "image/png", new byte[]{1, 2, 3});
    }
}
