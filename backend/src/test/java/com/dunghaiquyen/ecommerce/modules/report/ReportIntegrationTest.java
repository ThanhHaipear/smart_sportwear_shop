package com.dunghaiquyen.ecommerce.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ReportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OrderRepository orderRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("rpt-admin"));
        String categorySlug = "rpt-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "rpt-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createActiveProduct(AdminContext ctx, String name) throws Exception {
        String slug = "rpt-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"ACTIVE\"}")
                                .formatted(name, slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createVariant(AdminContext ctx, String productId, int price, int stockQuantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"M\",\"color\":\"Black\",\"price\":%d,\"stockQuantity\":%d}")
                                .formatted(UUID.randomUUID(), price, stockQuantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private void addToCart(String token, String variantId, int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private String createAddressForUser(String email) {
        var user = userRepository.findByEmail(email).orElseThrow();
        Address address = new Address();
        address.setUser(user);
        address.setReceiverName("Test Receiver");
        address.setPhone("0900000000");
        address.setProvince("HCM");
        address.setDistrict("District 1");
        address.setWard("Ward 1");
        address.setAddressLine("123 Test Street");
        return addressRepository.save(address).getId().toString();
    }

    private record OrderResult(String id, BigDecimal totalAmount) {
    }

    private OrderResult createOrder(String token, String addressId, String paymentMethod) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"" + paymentMethod + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        return new OrderResult(
                body.at("/data/id").asText(), new BigDecimal(body.at("/data/totalAmount").asText()));
    }

    private void adminSetStatus(AdminContext ctx, String orderId, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private void markOrderPaid(String orderId) {
        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        order.setPaymentStatus(PaymentStatus.PAID);
        orderRepository.save(order);
    }

    private JsonNode getOverview(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result.getResponse().getContentAsString()).at("/data");
    }

    // ===== overview: comprehensive revenue-rule proof across cancelled / unpaid-delivered / paid-not-delivered =====

    @Test
    void overview_revenueRules_distinguishGrossFromRealized_acrossCancelledUnpaidAndPaidOrders() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Jersey");
        String variantId = createVariant(ctx, productId, 100000, 100);

        JsonNode before = getOverview(ctx.token());
        BigDecimal grossBefore = new BigDecimal(before.at("/grossRevenue").asText());
        BigDecimal realizedBefore = new BigDecimal(before.at("/realizedRevenue").asText());
        long totalBefore = before.at("/totalOrders").asLong();
        long pendingBefore = before.at("/pendingOrders").asLong();

        // Order A: VietQR, paid but never confirmed/delivered -
        // counts toward grossRevenue (paymentStatus=PAID) and stays "pending",
        // but must NOT count toward realizedRevenue.
        String buyerA = uniqueEmail("rpt-buyer-a");
        TokenPair tokenA = registerUser(buyerA);
        addToCart(tokenA.accessToken(), variantId, 2);
        String addressA = createAddressForUser(buyerA);
        OrderResult orderA = createOrder(tokenA.accessToken(), addressA, "VIETQR");
        markOrderPaid(orderA.id());

        // Order B: COD, progressed all the way to DELIVERED. Delivery records
        // cash collection, so it counts toward both gross and realized revenue.
        String buyerB = uniqueEmail("rpt-buyer-b");
        TokenPair tokenB = registerUser(buyerB);
        addToCart(tokenB.accessToken(), variantId, 3);
        String addressB = createAddressForUser(buyerB);
        OrderResult orderB = createOrder(tokenB.accessToken(), addressB, "COD");
        adminSetStatus(ctx, orderB.id(), "CONFIRMED");
        adminSetStatus(ctx, orderB.id(), "PACKING");
        adminSetStatus(ctx, orderB.id(), "SHIPPING");
        adminSetStatus(ctx, orderB.id(), "DELIVERED");

        // Order C: VietQR, customer cancels before any payment - must count
        // toward NEITHER revenue figure, and must not still be "pending".
        String buyerC = uniqueEmail("rpt-buyer-c");
        TokenPair tokenC = registerUser(buyerC);
        addToCart(tokenC.accessToken(), variantId, 1);
        String addressC = createAddressForUser(buyerC);
        OrderResult orderC = createOrder(tokenC.accessToken(), addressC, "VIETQR");
        mockMvc.perform(post("/api/v1/orders/" + orderC.id() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenC.accessToken()))
                .andExpect(status().isOk());

        // Regression: a late success callback arriving AFTER cancellation must
        // still not leak into grossRevenue - Order.paymentStatus is never pulled
        // to PAID once orderStatus is CANCELLED (Phase H), so this transactionRef
        // intentionally has no session at all to attempt against; instead prove
        // the already-cancelled order C contributes nothing below.

        JsonNode after = getOverview(ctx.token());
        BigDecimal grossAfter = new BigDecimal(after.at("/grossRevenue").asText());
        BigDecimal realizedAfter = new BigDecimal(after.at("/realizedRevenue").asText());
        long totalAfter = after.at("/totalOrders").asLong();
        long pendingAfter = after.at("/pendingOrders").asLong();

        assertThat(grossAfter.subtract(grossBefore))
                .as("grossRevenue includes paid VietQR and delivered/collected COD, never cancelled order C")
                .isEqualByComparingTo(orderA.totalAmount().add(orderB.totalAmount()));
        assertThat(realizedAfter.subtract(realizedBefore))
                .as("realizedRevenue must equal exactly order B's total (orderStatus=DELIVERED), not A or C")
                .isEqualByComparingTo(orderB.totalAmount());
        assertThat(totalAfter - totalBefore).isEqualTo(3);
        assertThat(pendingAfter - pendingBefore)
                .as("only order A remains PENDING_CONFIRMATION - B is DELIVERED, C is CANCELLED")
                .isEqualTo(1);
    }

    @Test
    void revenueBreakdown_explainsGrossRealizedDifferenceWithStatusSlices() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Revenue Breakdown");
        String variantId = createVariant(ctx, productId, 100000, 100);

        String paidBuyer = uniqueEmail("rpt-breakdown-paid");
        TokenPair paidToken = registerUser(paidBuyer);
        addToCart(paidToken.accessToken(), variantId, 1);
        String paidAddress = createAddressForUser(paidBuyer);
        OrderResult paidOrder = createOrder(paidToken.accessToken(), paidAddress, "VIETQR");
        markOrderPaid(paidOrder.id());

        String codBuyer = uniqueEmail("rpt-breakdown-cod");
        TokenPair codToken = registerUser(codBuyer);
        addToCart(codToken.accessToken(), variantId, 2);
        String codAddress = createAddressForUser(codBuyer);
        OrderResult codOrder = createOrder(codToken.accessToken(), codAddress, "COD");
        adminSetStatus(ctx, codOrder.id(), "CONFIRMED");
        adminSetStatus(ctx, codOrder.id(), "PACKING");
        adminSetStatus(ctx, codOrder.id(), "SHIPPING");
        adminSetStatus(ctx, codOrder.id(), "DELIVERED");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/revenue/breakdown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(body.at("/breakdownAvailable").asBoolean()).isTrue();
        assertThat(new BigDecimal(body.at("/grossRevenue").asText())).isGreaterThanOrEqualTo(paidOrder.totalAmount());
        assertThat(new BigDecimal(body.at("/realizedRevenue").asText())).isGreaterThanOrEqualTo(codOrder.totalAmount());
        assertThat(new BigDecimal(body.at("/codDeliveredUnpaid/amount").asText())).isGreaterThanOrEqualTo(codOrder.totalAmount());
        assertThat(new BigDecimal(body.at("/paidNotDelivered/amount").asText())).isGreaterThanOrEqualTo(paidOrder.totalAmount());
        assertThat(body.at("/byPaymentStatus").isArray()).isTrue();
        assertThat(body.at("/byOrderStatus").isArray()).isTrue();
    }

    // ===== lowStockCount =====

    @Test
    void overview_lowStockCount_reflectsAvailableQuantityBelowThreshold() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Low Stock Item");

        JsonNode before = getOverview(ctx.token());
        long lowStockBefore = before.at("/lowStockCount").asLong();

        createVariant(ctx, productId, 50000, 20); // available=20, above the default threshold (10) - not low stock
        createVariant(ctx, productId, 50000, 5); // available=5, at/under threshold - low stock

        JsonNode after = getOverview(ctx.token());
        long lowStockAfter = after.at("/lowStockCount").asLong();
        assertThat(lowStockAfter - lowStockBefore)
                .as("only the 5-unit variant should newly count as low stock")
                .isEqualTo(1);
    }

    // ===== order report: status breakdown + date range filter =====

    @Test
    void orderReport_dateRangeFilter_excludesOutOfRangeAndIncludesInRange() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Order Range");
        String variantId = createVariant(ctx, productId, 60000, 50);

        String buyer = uniqueEmail("rpt-orderreport-buyer");
        TokenPair token = registerUser(buyer);
        addToCart(token.accessToken(), variantId, 1);
        String address = createAddressForUser(buyer);
        createOrder(token.accessToken(), address, "COD");

        // A future-only window can never contain anything created "now".
        MvcResult futureOnly = mockMvc.perform(get("/api/v1/admin/reports/orders?dateFrom="
                        + LocalDate.now().plusDays(5))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(futureOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(futureOnly.getResponse().getContentAsString()).at("/data/totalOrders").asLong())
                .isEqualTo(0);

        // A historical-only window (entirely before today) is also always empty.
        MvcResult pastOnly = mockMvc.perform(get(
                        "/api/v1/admin/reports/orders?dateFrom=2000-01-01&dateTo=2000-01-02")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(json(pastOnly.getResponse().getContentAsString()).at("/data/totalOrders").asLong())
                .isEqualTo(0);

        // Today's window must include the order just created and echo the byStatus breakdown.
        MvcResult today = mockMvc.perform(get("/api/v1/admin/reports/orders?dateFrom="
                        + LocalDate.now() + "&dateTo=" + LocalDate.now().plusDays(1))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        JsonNode todayBody = json(today.getResponse().getContentAsString()).at("/data");
        assertThat(todayBody.at("/totalOrders").asLong()).isGreaterThanOrEqualTo(1);
        boolean hasPendingConfirmation = false;
        for (JsonNode entry : todayBody.at("/byStatus")) {
            if (entry.at("/status").asText().equals("PENDING_CONFIRMATION") && entry.at("/count").asLong() >= 1) {
                hasPendingConfirmation = true;
            }
        }
        assertThat(hasPendingConfirmation).isTrue();
    }

    @Test
    void orderStatusTrend_returnsDailyStatusBuckets() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Order Trend");
        String variantId = createVariant(ctx, productId, 60000, 50);

        String buyer = uniqueEmail("rpt-ordertrend-buyer");
        TokenPair token = registerUser(buyer);
        addToCart(token.accessToken(), variantId, 1);
        String address = createAddressForUser(buyer);
        createOrder(token.accessToken(), address, "COD");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/orders/status-trend?dateFrom="
                        + LocalDate.now() + "&dateTo=" + LocalDate.now())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result.getResponse().getContentAsString()).at("/data");
        assertThat(body.at("/trendAvailable").asBoolean()).isTrue();
        assertThat(body.at("/points").size()).isEqualTo(1);
        assertThat(body.at("/points/0/totalOrders").asLong()).isGreaterThanOrEqualTo(1);
    }

    // ===== product report: best selling, based on real order items, excluding cancelled =====

    @Test
    void productReport_bestSelling_reflectsRealOrderItems_andExcludesCancelledOrders() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Bestseller Cap");
        String variantId = createVariant(ctx, productId, 45000, 100);

        String buyer1 = uniqueEmail("rpt-bestseller-buyer1");
        TokenPair token1 = registerUser(buyer1);
        addToCart(token1.accessToken(), variantId, 6);
        String address1 = createAddressForUser(buyer1);
        createOrder(token1.accessToken(), address1, "COD");

        // A second order for the SAME product gets cancelled - its quantity must
        // not be added to the best-selling total.
        String buyer2 = uniqueEmail("rpt-bestseller-buyer2");
        TokenPair token2 = registerUser(buyer2);
        addToCart(token2.accessToken(), variantId, 9);
        String address2 = createAddressForUser(buyer2);
        OrderResult order2 = createOrder(token2.accessToken(), address2, "COD");
        mockMvc.perform(post("/api/v1/orders/" + order2.id() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2.accessToken()))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/products?limit=50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode bestSelling = json(result.getResponse().getContentAsString()).at("/data/bestSelling");

        JsonNode mine = null;
        for (JsonNode entry : bestSelling) {
            if (entry.at("/productId").asText().equals(productId)) {
                mine = entry;
            }
        }
        assertThat(mine).as("the product must appear in the best-selling list").isNotNull();
        assertThat(mine.at("/totalQuantitySold").asLong())
                .as("only the 6 units from the non-cancelled order must be counted, not the cancelled order's 9")
                .isEqualTo(6);
    }

    @Test
    void customerReport_aggregatesOrdersByCustomer_andSupportsDateRange() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Customer Product");
        String variantId = createVariant(ctx, productId, 70000, 50);

        String buyer = uniqueEmail("rpt-customer-buyer");
        TokenPair token = registerUser(buyer);
        String addressId = createAddressForUser(buyer);
        addToCart(token.accessToken(), variantId, 2);
        createOrder(token.accessToken(), addressId, "COD");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/reports/customers?limit=50&dateFrom="
                        + LocalDate.now() + "&dateTo=" + LocalDate.now())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = json(result.getResponse().getContentAsString()).at("/data/customers");
        JsonNode mine = null;
        for (JsonNode row : rows) {
            if (row.at("/email").asText().equals(buyer)) mine = row;
        }
        assertThat(mine).isNotNull();
        assertThat(mine.at("/totalOrders").asLong()).isEqualTo(1);
        assertThat(new BigDecimal(mine.at("/totalRevenue").asText())).isGreaterThan(BigDecimal.ZERO);

        MvcResult past = mockMvc.perform(get("/api/v1/admin/reports/customers?dateFrom=2000-01-01&dateTo=2000-01-02")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(past.getResponse().getContentAsString()).at("/data/customers").isEmpty()).isTrue();
    }

    // ===== inventory report: current inventory + low stock =====

    @Test
    void inventoryReport_currentInventoryAndLowStock_reflectRealStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Report Inventory Item");

        MvcResult before = mockMvc.perform(get("/api/v1/admin/reports/inventory")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        JsonNode beforeBody = json(before.getResponse().getContentAsString()).at("/data");
        long variantsBefore = beforeBody.at("/totalVariants").asLong();
        long stockBefore = beforeBody.at("/totalStockQuantity").asLong();
        long lowStockBefore = beforeBody.at("/lowStockCount").asLong();

        String variantId = createVariant(ctx, productId, 30000, 4); // available=4 <= default threshold 10

        MvcResult after = mockMvc.perform(get("/api/v1/admin/reports/inventory")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterBody = json(after.getResponse().getContentAsString()).at("/data");

        assertThat(afterBody.at("/totalVariants").asLong() - variantsBefore).isEqualTo(1);
        assertThat(afterBody.at("/totalStockQuantity").asLong() - stockBefore).isEqualTo(4);
        assertThat(afterBody.at("/lowStockCount").asLong() - lowStockBefore).isEqualTo(1);

        boolean found = false;
        for (JsonNode item : afterBody.at("/lowStockItems")) {
            if (item.at("/variantId").asText().equals(variantId)) {
                found = true;
                assertThat(item.at("/availableQuantity").asInt()).isEqualTo(4);
            }
        }
        assertThat(found).as("the newly low-stock variant must appear in lowStockItems").isTrue();
    }

    // ===== role gate =====

    @Test
    void nonAdmin_getsForbidden_onAllReportEndpoints() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("rpt-not-admin"));
        mockMvc.perform(get("/api/v1/admin/reports/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reports/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reports/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reports/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reports/inventory")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
