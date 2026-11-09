package com.dunghaiquyen.ecommerce.infra.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.brand.repository.BrandRepository;
import com.dunghaiquyen.ecommerce.modules.category.repository.CategoryRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Tag("phase0-excluded")
@Tag("dormant")
class SeedDataIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SeedDataService seedDataService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    @Autowired
    private ProductImageRepository imageRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void seed_isIdempotent_andProducesUsefulDemoData() throws Exception {
        String baselineAdminToken = registerAdminAndGetAccessToken(uniqueEmail("seed-report-admin"));
        ReportSnapshot baselineOverview = fetchOverview(baselineAdminToken);
        boolean seedOrdersAlreadyExisted = orderRepository.existsByNote("[SEED] pending-vnpay-paid");

        SeedDataService.SeedSummary first = seedDataService.seed();
        assertThat(first.users()).isEqualTo(6);
        assertThat(first.products()).isEqualTo(5);
        assertThat(first.variants()).isEqualTo(10);
        assertThat(first.orders()).isEqualTo(4);
        assertThat(categoryRepository.findBySlug("running")).isPresent();
        assertThat(categoryRepository.findBySlug("football")).isPresent();
        assertThat(categoryRepository.findBySlug("training")).isPresent();
        assertThat(brandRepository.findBySlug("nike")).isPresent();
        assertThat(brandRepository.findBySlug("adidas")).isPresent();
        assertThat(brandRepository.findBySlug("under-armour")).isPresent();

        SeedDataService.SeedSummary second = seedDataService.seed();
        assertThat(second.users()).isEqualTo(first.users());
        assertThat(second.products()).isEqualTo(first.products());
        assertThat(second.variants()).isEqualTo(first.variants());
        assertThat(second.orders()).isEqualTo(first.orders());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123"}
                                """.formatted(SeedDataService.DEMO_ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = json(login.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();

        MvcResult overview = mockMvc.perform(get("/api/v1/admin/reports/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        ReportSnapshot seededOverview = ReportSnapshot.from(json(overview.getResponse().getContentAsString()).at("/data"));
        int expectedOrderDelta = seedOrdersAlreadyExisted ? 0 : 4;
        int expectedPendingDelta = seedOrdersAlreadyExisted ? 0 : 1;
        int expectedLowStockDelta = seedOrdersAlreadyExisted ? 0 : 2;
        // Gross includes both the paid VNPay order (780,000) and delivered COD
        // order (3 x 129,000), whose payment status becomes PAID on delivery.
        BigDecimal expectedGrossDelta = seedOrdersAlreadyExisted ? BigDecimal.ZERO : new BigDecimal("1167000");
        BigDecimal expectedRealizedDelta = seedOrdersAlreadyExisted ? BigDecimal.ZERO : new BigDecimal("387000");

        assertThat(seededOverview.totalOrders() - baselineOverview.totalOrders()).isEqualTo(expectedOrderDelta);
        assertThat(seededOverview.pendingOrders() - baselineOverview.pendingOrders()).isEqualTo(expectedPendingDelta);
        assertThat(seededOverview.lowStockCount() - baselineOverview.lowStockCount()).isEqualTo(expectedLowStockDelta);
        assertThat(seededOverview.grossRevenue().subtract(baselineOverview.grossRevenue()))
                .isEqualByComparingTo(expectedGrossDelta);
        assertThat(seededOverview.realizedRevenue().subtract(baselineOverview.realizedRevenue()))
                .isEqualByComparingTo(expectedRealizedDelta);

        MvcResult products = mockMvc.perform(get("/api/v1/admin/reports/products?limit=50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bestSelling = json(products.getResponse().getContentAsString()).at("/data/bestSelling");
        assertThat(bestSelling).isNotEmpty();
        JsonNode seededProduct = findBestSellingProduct(bestSelling, "Tat Tap Training Grip");
        assertThat(seededProduct).isNotNull();
        assertThat(seededProduct.at("/totalQuantitySold").asInt()).isGreaterThanOrEqualTo(3);
    }

    private ReportSnapshot fetchOverview(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/overview")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return ReportSnapshot.from(json(result.getResponse().getContentAsString()).at("/data"));
    }

    private JsonNode findBestSellingProduct(JsonNode bestSelling, String productName) {
        Iterator<JsonNode> iterator = bestSelling.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (productName.equals(item.at("/productName").asText())) {
                return item;
            }
        }
        return null;
    }

    private record ReportSnapshot(
            BigDecimal grossRevenue,
            BigDecimal realizedRevenue,
            int totalOrders,
            int pendingOrders,
            int lowStockCount) {

        private static ReportSnapshot from(JsonNode node) {
            return new ReportSnapshot(
                    new BigDecimal(node.at("/grossRevenue").asText()),
                    new BigDecimal(node.at("/realizedRevenue").asText()),
                    node.at("/totalOrders").asInt(),
                    node.at("/pendingOrders").asInt(),
                    node.at("/lowStockCount").asInt());
        }
    }
}
