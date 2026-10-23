package com.dunghaiquyen.ecommerce.visualsearch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.product.dto.ProductListItemResponse;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class VisualSearchServiceTest {

    @Test
    void fetchesWideCandidatePoolBeforeApplyingCommerceFilters() {
        VisualSearchClient client = mock(VisualSearchClient.class);
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getContentType()).thenReturn("image/jpeg");
        when(client.search(image, VisualSearchService.CANDIDATE_LIMIT)).thenReturn(List.of());
        VisualSearchService service = new VisualSearchService(
                new VisualSearchProperties(true, "http://localhost", "token", 5, 10),
                client,
                mock(ProductService.class),
                mock(ProductRepository.class));

        assertThat(service.search(image, 20, null, null, null, null)).isEmpty();
        verify(client).search(image, 50);
    }

    @Test
    void filtersInactiveAndHardFiltersThenReturnsCurrentCommerceData() {
        VisualSearchClient client = mock(VisualSearchClient.class);
        ProductService productService = mock(ProductService.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getContentType()).thenReturn("image/png");
        UUID categoryId = UUID.randomUUID();
        UUID matchingId = UUID.randomUUID();
        UUID wrongCategoryId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        UUID matchedImageId = UUID.randomUUID();
        when(client.search(image, 50)).thenReturn(List.of(
                new VisualSearchCandidate(matchingId, matchedImageId, "https://example/matched.jpg", 0.93),
                new VisualSearchCandidate(wrongCategoryId, UUID.randomUUID(), "https://example/other.jpg", 0.92),
                new VisualSearchCandidate(inactiveId, UUID.randomUUID(), "https://example/inactive.jpg", 0.99)));

        ProductListItemResponse matching = listItem(matchingId, "Current name", 400_000, 500_000);
        ProductListItemResponse wrongCategory = listItem(wrongCategoryId, "Other", 400_000, 500_000);
        when(productService.assembleListItemsByIds(List.of(matchingId, wrongCategoryId, inactiveId), ProductStatus.ACTIVE))
                .thenReturn(List.of(matching, wrongCategory));

        Product matchingEntity = product(categoryId, Gender.MEN, true);
        Product otherEntity = product(UUID.randomUUID(), Gender.MEN, true);
        Product inactiveEntity = product(categoryId, Gender.MEN, true);
        withId(matchingEntity, matchingId);
        withId(otherEntity, wrongCategoryId);
        withId(inactiveEntity, inactiveId);
        when(productRepository.findAllById(List.of(matchingId, wrongCategoryId, inactiveId)))
                .thenReturn(List.of(matchingEntity, otherEntity, inactiveEntity));

        VisualSearchService service = service(client, productService, productRepository);
        List<VisualSearchResult> results = service.search(
                image, 20, categoryId, Gender.MEN, BigDecimal.valueOf(350_000), BigDecimal.valueOf(550_000));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().product().name()).isEqualTo("Current name");
        assertThat(results.getFirst().matchedImageId()).isEqualTo(matchedImageId);
        assertThat(results.getFirst().matchedImageUrl()).isEqualTo("https://example/matched.jpg");
        assertThat(results.getFirst().similarity()).isEqualTo(0.93);
    }

    @Test
    void keepsSimilarityOrderWithinStockGroupsAndSurfacesPurchasableProductsFirst() {
        VisualSearchClient client = mock(VisualSearchClient.class);
        ProductService productService = mock(ProductService.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getContentType()).thenReturn("image/webp");
        UUID soldOutId = UUID.randomUUID();
        UUID inStockId = UUID.randomUUID();
        when(client.search(image, 50)).thenReturn(List.of(
                new VisualSearchCandidate(soldOutId, UUID.randomUUID(), "sold-out", 0.99),
                new VisualSearchCandidate(inStockId, UUID.randomUUID(), "in-stock", 0.90)));
        when(productService.assembleListItemsByIds(List.of(soldOutId, inStockId), ProductStatus.ACTIVE))
                .thenReturn(List.of(
                        listItem(soldOutId, "Sold out", 100_000, 100_000),
                        listItem(inStockId, "In stock", 100_000, 100_000)));
        Product soldOut = product(UUID.randomUUID(), Gender.UNISEX, false);
        Product inStock = product(UUID.randomUUID(), Gender.UNISEX, true);
        withId(soldOut, soldOutId);
        withId(inStock, inStockId);
        when(productRepository.findAllById(List.of(soldOutId, inStockId)))
                .thenReturn(List.of(soldOut, inStock));

        List<VisualSearchResult> results = service(client, productService, productRepository)
                .search(image, 20, null, null, null, null);

        assertThat(results).extracting(result -> result.product().id())
                .containsExactly(inStockId, soldOutId);
    }

    @Test
    void appliesCategoryGenderAndPriceFiltersIndependently() {
        VisualSearchClient client = mock(VisualSearchClient.class);
        ProductService productService = mock(ProductService.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        MultipartFile image = validImage("image/jpeg");
        UUID categoryId = UUID.randomUUID();
        UUID matchingId = UUID.randomUUID();
        UUID wrongCategoryId = UUID.randomUUID();
        UUID wrongGenderId = UUID.randomUUID();
        UUID belowPriceId = UUID.randomUUID();
        UUID abovePriceId = UUID.randomUUID();
        List<UUID> ids = List.of(matchingId, wrongCategoryId, wrongGenderId, belowPriceId, abovePriceId);
        when(client.search(image, 50)).thenReturn(ids.stream()
                .map(id -> new VisualSearchCandidate(id, UUID.randomUUID(), "https://example/" + id, 0.8))
                .toList());
        when(productService.assembleListItemsByIds(ids, ProductStatus.ACTIVE)).thenReturn(List.of(
                listItem(matchingId, "Matching", 400_000, 500_000),
                listItem(wrongCategoryId, "Wrong category", 400_000, 500_000),
                listItem(wrongGenderId, "Wrong gender", 400_000, 500_000),
                listItem(belowPriceId, "Below range", 100_000, 200_000),
                listItem(abovePriceId, "Above range", 600_000, 700_000)));
        List<Product> entities = List.of(
                withId(product(categoryId, Gender.MEN, true), matchingId),
                withId(product(UUID.randomUUID(), Gender.MEN, true), wrongCategoryId),
                withId(product(categoryId, Gender.WOMEN, true), wrongGenderId),
                withId(product(categoryId, Gender.MEN, true), belowPriceId),
                withId(product(categoryId, Gender.MEN, true), abovePriceId));
        when(productRepository.findAllById(ids)).thenReturn(entities);

        List<VisualSearchResult> results = service(client, productService, productRepository).search(
                image,
                20,
                categoryId,
                Gender.MEN,
                BigDecimal.valueOf(350_000),
                BigDecimal.valueOf(550_000));

        assertThat(results).extracting(result -> result.product().id()).containsExactly(matchingId);
    }

    @Test
    void preservesBackendCandidateOrderWhenStockAndSimilarityAreTied() {
        VisualSearchClient client = mock(VisualSearchClient.class);
        ProductService productService = mock(ProductService.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        MultipartFile image = validImage("image/webp");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        List<UUID> ids = List.of(firstId, secondId);
        when(client.search(image, 50)).thenReturn(List.of(
                new VisualSearchCandidate(firstId, UUID.randomUUID(), "first", 0.8),
                new VisualSearchCandidate(secondId, UUID.randomUUID(), "second", 0.8)));
        when(productService.assembleListItemsByIds(ids, ProductStatus.ACTIVE)).thenReturn(List.of(
                listItem(firstId, "First", 100_000, 100_000),
                listItem(secondId, "Second", 100_000, 100_000)));
        Product first = withId(product(UUID.randomUUID(), Gender.UNISEX, true), firstId);
        Product second = withId(product(UUID.randomUUID(), Gender.UNISEX, true), secondId);
        when(productRepository.findAllById(ids)).thenReturn(List.of(first, second));

        List<VisualSearchResult> results = service(client, productService, productRepository)
                .search(image, 20, null, null, null, null);

        assertThat(results).extracting(result -> result.product().id()).containsExactly(firstId, secondId);
    }

    private static VisualSearchService service(
            VisualSearchClient client, ProductService productService, ProductRepository productRepository) {
        return new VisualSearchService(
                new VisualSearchProperties(true, "http://localhost", "token", 5, 10),
                client, productService, productRepository);
    }

    private static ProductListItemResponse listItem(UUID id, String name, long minPrice, long maxPrice) {
        return new ProductListItemResponse(
                id, name, "slug-" + id, null, null, null, "current-thumbnail",
                BigDecimal.valueOf(minPrice), BigDecimal.valueOf(maxPrice), null, null, null,
                1, ProductStatus.ACTIVE, null);
    }

    private static Product product(UUID categoryId, Gender gender, boolean inStock) {
        Category category = mock(Category.class);
        when(category.getId()).thenReturn(categoryId);
        ProductVariant variant = mock(ProductVariant.class);
        when(variant.getStatus()).thenReturn(VariantStatus.ACTIVE);
        when(variant.getStockQuantity()).thenReturn(inStock ? 2 : 1);
        when(variant.getReservedQuantity()).thenReturn(1);
        Product product = mock(Product.class);
        when(product.getCategory()).thenReturn(category);
        when(product.getGender()).thenReturn(gender);
        when(product.getVariants()).thenReturn(List.of(variant));
        return product;
    }

    private static Product withId(Product product, UUID id) {
        when(product.getId()).thenReturn(id);
        return product;
    }

    private static MultipartFile validImage(String contentType) {
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getContentType()).thenReturn(contentType);
        return image;
    }
}
