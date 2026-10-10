package com.dunghaiquyen.ecommerce.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class OrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("order-admin"));
        String categorySlug = "order-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "order-brand-" + UUID.randomUUID();
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
        String slug = "order-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
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
        String variantId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return variantId;
    }

    private void addToCart(String token, String variantId, int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private void createCombo(AdminContext ctx, String name, int discountAmount, String... productIds) throws Exception {
        String productIdsJson = java.util.Arrays.stream(productIds)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(post("/api/v1/admin/combos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "name":"%s",
                                  "description":"test combo",
                                  "discountAmount":%d,
                                  "status":"ACTIVE",
                                  "productIds":[%s]
                                }
                                """
                                        .formatted(name, discountAmount, productIdsJson)))
                .andExpect(status().isOk());
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

    private String createOrderBody(String addressId) {
        return "{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\",\"note\":\"Leave at door\"}";
    }

    @Test
    void buyNow_previewsAndCreatesOrderWithoutAddingOrRemovingCartItems() throws Exception {
        AdminContext ctx = setUpAdmin();
        String buyNowProductId = createActiveProduct(ctx, "Buy Now Shirt");
        String buyNowVariantId = createVariant(ctx, buyNowProductId, 125000, 5);
        String cartProductId = createActiveProduct(ctx, "Existing Cart Shorts");
        String cartVariantId = createVariant(ctx, cartProductId, 80000, 5);

        String email = uniqueEmail("buy-now");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), cartVariantId, 1);
        String addressId = createAddressForUser(email);

        String buyNowSource = "\"buyNowVariantId\":\"" + buyNowVariantId + "\",\"buyNowQuantity\":2";
        MvcResult preview = mockMvc.perform(post("/api/v1/checkout/preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + buyNowSource + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode previewBody = json(preview.getResponse().getContentAsString());
        assertThat(previewBody.at("/data/items")).hasSize(1);
        assertThat(previewBody.at("/data/items/0/variantId").asText()).isEqualTo(buyNowVariantId);
        assertThat(previewBody.at("/data/subtotal").asInt()).isEqualTo(250000);

        MvcResult order = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"," + buyNowSource + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(json(order.getResponse().getContentAsString()).at("/data/items/0/variantId").asText())
                .isEqualTo(buyNowVariantId);

        MvcResult cart = mockMvc.perform(get("/api/v1/cart")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode cartBody = json(cart.getResponse().getContentAsString());
        assertThat(cartBody.at("/data/items")).hasSize(1);
        assertThat(cartBody.at("/data/items/0/variantId").asText()).isEqualTo(cartVariantId);
        assertThat(variantRepository.findById(UUID.fromString(buyNowVariantId)).orElseThrow().getReservedQuantity())
                .isEqualTo(2);
    }

    @Test
    void buyNow_outOfStockVariant_isRejectedByPreviewAndOrder() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Sold Out Buy Now");
        String variantId = createVariant(ctx, productId, 99000, 0);
        String email = uniqueEmail("buy-now-sold-out");
        TokenPair buyer = registerUser(email);
        String addressId = createAddressForUser(email);
        String source = "\"buyNowVariantId\":\"" + variantId + "\",\"buyNowQuantity\":1";

        MvcResult preview = mockMvc.perform(post("/api/v1/checkout/preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + source + "}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(preview.getResponse().getContentAsString()).at("/data/canCheckout").asBoolean()).isFalse();

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"paymentMethod\":\"COD\"," + source + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void buyNow_twoCustomersRaceForLastUnit_onlyOneOrderReservesStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Last Unit Jersey");
        String variantId = createVariant(ctx, productId, 250000, 1);

        String firstEmail = uniqueEmail("race-first");
        String secondEmail = uniqueEmail("race-second");
        TokenPair firstBuyer = registerUser(firstEmail);
        TokenPair secondBuyer = registerUser(secondEmail);
        String firstAddress = createAddressForUser(firstEmail);
        String secondAddress = createAddressForUser(secondEmail);

        Callable<MvcResult> firstCheckout = () -> mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstBuyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + firstAddress
                                + "\",\"paymentMethod\":\"COD\",\"buyNowVariantId\":\"" + variantId
                                + "\",\"buyNowQuantity\":1}"))
                .andReturn();
        Callable<MvcResult> secondCheckout = () -> mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondBuyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + secondAddress
                                + "\",\"paymentMethod\":\"COD\",\"buyNowVariantId\":\"" + variantId
                                + "\",\"buyNowQuantity\":1}"))
                .andReturn();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> futures = pool.invokeAll(List.of(firstCheckout, secondCheckout));
        pool.shutdown();

        List<Integer> statuses = new java.util.ArrayList<>();
        for (Future<MvcResult> future : futures) {
            statuses.add(future.get().getResponse().getStatus());
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity()).isEqualTo(1);
        assertThat(variant.getStockQuantity()).isEqualTo(1);
    }

    @Test
    void checkoutPreview_withApplicableCombo_returnsDiscountedTotal() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId1 = createActiveProduct(ctx, "Combo Shirt");
        String variantId1 = createVariant(ctx, productId1, 120000, 10);
        String productId2 = createActiveProduct(ctx, "Combo Shorts");
        String variantId2 = createVariant(ctx, productId2, 80000, 10);
        createCombo(ctx, "Summer Combo", 50000, productId1, productId2);

        String email = uniqueEmail("combo-preview");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId1, 1);
        addToCart(buyer.accessToken(), variantId2, 1);

        MvcResult result = mockMvc.perform(post("/api/v1/checkout/preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/subtotal").asDouble()).isEqualTo(200000.0);
        assertThat(body.at("/data/discountAmount").asDouble()).isEqualTo(50000.0);
        assertThat(body.at("/data/totalAmount").asDouble()).isEqualTo(150000.0);
        assertThat(body.at("/data/canCheckout").asBoolean()).isTrue();
    }

    @Test
    void createOrder_withApplicableCombo_persistsDiscountAmount() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId1 = createActiveProduct(ctx, "Combo Shoe");
        String variantId1 = createVariant(ctx, productId1, 300000, 10);
        String productId2 = createActiveProduct(ctx, "Combo Socks");
        String variantId2 = createVariant(ctx, productId2, 50000, 10);
        createCombo(ctx, "Match Day Combo", 70000, productId1, productId2);

        String email = uniqueEmail("combo-order");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId1, 1);
        addToCart(buyer.accessToken(), variantId2, 1);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/subtotalAmount").asDouble()).isEqualTo(350000.0);
        assertThat(body.at("/data/discountAmount").asDouble()).isEqualTo(70000.0);
        assertThat(body.at("/data/totalAmount").asDouble()).isEqualTo(280000.0);
    }

    @Test
    void createOrder_withoutFullCombo_doesNotApplyDiscount_andClampPreventsNegativeTotal() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId1 = createActiveProduct(ctx, "Clamp Tee");
        String variantId1 = createVariant(ctx, productId1, 90000, 10);
        String productId2 = createActiveProduct(ctx, "Clamp Shorts");
        String variantId2 = createVariant(ctx, productId2, 60000, 10);
        String productId3 = createActiveProduct(ctx, "Clamp Cap");
        createCombo(ctx, "Need Three Products", 40000, productId1, productId2, productId3);
        createCombo(ctx, "Huge Discount", 999999, productId1, productId2);

        String email = uniqueEmail("combo-clamp");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId1, 1);
        addToCart(buyer.accessToken(), variantId2, 1);
        String addressId = createAddressForUser(email);

        MvcResult preview = mockMvc.perform(post("/api/v1/checkout/preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode previewBody = json(preview.getResponse().getContentAsString());
        assertThat(previewBody.at("/data/subtotal").asDouble()).isEqualTo(150000.0);
        assertThat(previewBody.at("/data/discountAmount").asDouble())
                .as("only the fully matched combo should apply, and it must be clamped to subtotal")
                .isEqualTo(150000.0);
        assertThat(previewBody.at("/data/totalAmount").asDouble()).isEqualTo(0.0);

        MvcResult order = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode orderBody = json(order.getResponse().getContentAsString());
        assertThat(orderBody.at("/data/discountAmount").asDouble()).isEqualTo(150000.0);
        assertThat(orderBody.at("/data/totalAmount").asDouble()).isEqualTo(0.0);
    }

    // ===== create order success: multiple items, snapshot correctness, stock reserved, cart cleared =====

    @Test
    void createOrder_fromCartWithMultipleItems_succeedsWithCorrectSnapshotsAndReservesStockAndClearsCart()
            throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId1 = createActiveProduct(ctx, "Shirt");
        String variantId1 = createVariant(ctx, productId1, 100000, 10);
        String productId2 = createActiveProduct(ctx, "Shorts");
        String variantId2 = createVariant(ctx, productId2, 50000, 5);

        String email = uniqueEmail("order-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId1, 2);
        addToCart(buyer.accessToken(), variantId2, 3);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/orderCode").asText()).isNotBlank();
        assertThat(body.at("/data/orderStatus").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(body.at("/data/paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(body.at("/data/paymentMethod").asText()).isEqualTo("COD");
        assertThat(body.at("/data/subtotalAmount").asDouble()).isEqualTo(350000.0);
        assertThat(body.at("/data/totalAmount").asDouble()).isEqualTo(350000.0);
        assertThat(body.at("/data/items")).hasSize(2);

        JsonNode items = body.at("/data/items");
        boolean foundShirt = false;
        boolean foundShorts = false;
        for (JsonNode item : items) {
            if (item.at("/sku").asText().startsWith("SKU-") && item.at("/productName").asText().equals("Shirt")) {
                foundShirt = true;
                assertThat(item.at("/quantity").asInt()).isEqualTo(2);
                assertThat(item.at("/unitPrice").asDouble()).isEqualTo(100000.0);
                assertThat(item.at("/lineTotal").asDouble()).isEqualTo(200000.0);
                assertThat(item.at("/size").asText()).isEqualTo("M");
                assertThat(item.at("/color").asText()).isEqualTo("Black");
            }
            if (item.at("/productName").asText().equals("Shorts")) {
                foundShorts = true;
                assertThat(item.at("/quantity").asInt()).isEqualTo(3);
                assertThat(item.at("/unitPrice").asDouble()).isEqualTo(50000.0);
                assertThat(item.at("/lineTotal").asDouble()).isEqualTo(150000.0);
            }
        }
        assertThat(foundShirt).isTrue();
        assertThat(foundShorts).isTrue();

        // Stock reserved, not yet deducted.
        var variant1 = variantRepository.findById(UUID.fromString(variantId1)).orElseThrow();
        assertThat(variant1.getReservedQuantity()).isEqualTo(2);
        assertThat(variant1.getStockQuantity()).isEqualTo(10);
        var variant2 = variantRepository.findById(UUID.fromString(variantId2)).orElseThrow();
        assertThat(variant2.getReservedQuantity()).isEqualTo(3);

        // Cart cleared.
        var userId = userRepository.findByEmail(email).orElseThrow().getId();
        var cart = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId())).isEmpty();
    }

    // ===== empty cart rejected, no order created =====

    @Test
    void createOrder_emptyCart_returns422_createsNoOrder() throws Exception {
        String email = uniqueEmail("order-empty-cart");
        TokenPair buyer = registerUser(email);
        String addressId = createAddressForUser(email);
        long before = orderRepository.count();

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Cart is empty");
        assertThat(orderRepository.count()).isEqualTo(before);
    }

    // ===== variant goes INACTIVE after add-to-cart: reject, no order, cart untouched =====

    @Test
    void createOrder_variantInactive_returns422_createsNoOrder_andDoesNotClearCart() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Cap");
        String variantId = createVariant(ctx, productId, 80000, 10);

        String email = uniqueEmail("order-inactive-variant");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        long before = orderRepository.count();
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(orderRepository.count()).isEqualTo(before);

        var userId = userRepository.findByEmail(email).orElseThrow().getId();
        var cart = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId()))
                .as("a rejected checkout must not clear the cart")
                .hasSize(1);
    }

    // ===== product goes DRAFT after add-to-cart: reject, no order =====

    @Test
    void createOrder_productDraft_returns422_createsNoOrder() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Hat");
        String variantId = createVariant(ctx, productId, 80000, 10);

        String email = uniqueEmail("order-draft-product");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);

        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());

        long before = orderRepository.count();
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(orderRepository.count()).isEqualTo(before);
    }

    // ===== insufficient stock at checkout time (stock dropped after add-to-cart) =====

    @Test
    void createOrder_insufficientStock_returns422_createsNoOrder_andDoesNotClearCart() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Bag");
        String variantId = createVariant(ctx, productId, 80000, 5);

        String email = uniqueEmail("order-insufficient-stock");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 5);
        String addressId = createAddressForUser(email);

        // Another customer buys all 5 units first, exhausting available stock via reservation.
        String otherEmail = uniqueEmail("order-other-buyer");
        TokenPair other = registerUser(otherEmail);
        addToCart(other.accessToken(), variantId, 5);
        String otherAddressId = createAddressForUser(otherEmail);
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(otherAddressId)))
                .andExpect(status().isCreated());

        long before = orderRepository.count();
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .contains("Insufficient stock");
        assertThat(orderRepository.count()).isEqualTo(before);

        var userId = userRepository.findByEmail(email).orElseThrow().getId();
        var cart = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId())).hasSize(1);
    }

    // ===== address not found / not owned =====

    @Test
    void createOrder_addressNotOwnedByCaller_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Socks");
        String variantId = createVariant(ctx, productId, 20000, 10);

        String ownerEmail = uniqueEmail("order-address-owner");
        registerUser(ownerEmail);
        String addressId = createAddressForUser(ownerEmail);

        String strangerEmail = uniqueEmail("order-address-stranger");
        TokenPair stranger = registerUser(strangerEmail);
        addToCart(stranger.accessToken(), variantId, 1);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== user can view own orders; cannot view others' =====

    @Test
    void user_canListAndViewOwnOrder_butNotAnotherUsersOrder() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Jacket");
        String variantId = createVariant(ctx, productId, 90000, 10);

        String ownerEmail = uniqueEmail("order-view-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult list = mockMvc.perform(get("/api/v1/orders/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(list.getResponse().getContentAsString()).at("/data")).hasSize(1);

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk());

        String strangerEmail = uniqueEmail("order-view-stranger");
        TokenPair stranger = registerUser(strangerEmail);
        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken()))
                .andExpect(status().isNotFound());
    }

    // ===== admin can list and view detail =====

    @Test
    void admin_canListAndViewAnyOrderDetail() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Gloves");
        String variantId = createVariant(ctx, productId, 40000, 10);

        String buyerEmail = uniqueEmail("order-admin-view-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult list = mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(list.getResponse().getContentAsString()).at("/data")).isNotEmpty();

        MvcResult productOrders = mockMvc.perform(get("/api/v1/admin/orders")
                        .param("productId", productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(productOrders.getResponse().getContentAsString()).at("/data/0/id").asText())
                .isEqualTo(orderId);

        MvcResult unrelatedProductOrders = mockMvc.perform(get("/api/v1/admin/orders")
                        .param("productId", java.util.UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(unrelatedProductOrders.getResponse().getContentAsString()).at("/data")).isEmpty();

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(detail.getResponse().getContentAsString());
        assertThat(body.at("/data/customerName").asText()).isEqualTo("Test User");
    }

    @Test
    void customer_cannotAccessAdminOrderEndpoints() throws Exception {
        TokenPair customer = registerUser(uniqueEmail("order-not-admin"));
        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ===== status transition: valid (PENDING_CONFIRMATION -> CONFIRMED) deducts real stock =====

    @Test
    void adminConfirmOrder_validTransition_deductsStockFromReservedAndFromTotal() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Sneakers");
        String variantId = createVariant(ctx, productId, 200000, 10);

        String buyerEmail = uniqueEmail("order-confirm-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 4);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        MvcResult confirmResult = mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"Confirmed by staff\"}"))
                .andReturn();
        assertThat(confirmResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(confirmResult.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CONFIRMED");

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getStockQuantity()).isEqualTo(6);
        assertThat(variant.getReservedQuantity()).isEqualTo(0);
    }

    // ===== admin may freely correct operational status; cancellation remains a separate workflow =====

    @Test
    void adminUpdateStatus_allowsCorrectionAcrossOperationalStatuses() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Belt");
        String variantId = createVariant(ctx, productId, 30000, 10);

        String buyerEmail = uniqueEmail("order-bad-transition");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        // Admin may correct/jump to any operational status before delivery.
        MvcResult result = mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPING\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("SHIPPING");

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PACKING\"}"))
                .andExpect(status().isOk());

        MvcResult corrected = mockMvc.perform(get("/api/v1/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(corrected.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("PACKING");

        MvcResult cancellation = mockMvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(cancellation.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CANCELLED");
    }

    // ===== cancel from PENDING_CONFIRMATION releases the reservation =====

    @Test
    void adminCancelOrder_fromPendingConfirmation_releasesReservedStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Watch");
        String variantId = createVariant(ctx, productId, 500000, 10);

        String buyerEmail = uniqueEmail("order-cancel-buyer");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 3);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        var afterReserve = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterReserve.getReservedQuantity()).isEqualTo(3);

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer requested cancellation\"}"))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);

        var afterCancel = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterCancel.getReservedQuantity()).isEqualTo(0);
        assertThat(afterCancel.getStockQuantity()).isEqualTo(10);

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isConflict());
    }

    // ===== concurrent checkouts on a low-stock variant must never reserve past stock =====

    @Test
    void concurrentCheckouts_onLowStockVariant_neverReserveMoreThanStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "LimitedEdition");
        String variantId = createVariant(ctx, productId, 1000000, 3);

        int attempts = 5;
        List<TokenPair> buyers = new java.util.ArrayList<>();
        List<String> addressIds = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String email = uniqueEmail("order-race-buyer-" + i);
            TokenPair buyer = registerUser(email);
            addToCart(buyer.accessToken(), variantId, 1);
            addressIds.add(createAddressForUser(email));
            buyers.add(buyer);
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            TokenPair buyer = buyers.get(i);
            String addressId = addressIds.get(i);
            tasks.add(() -> mockMvc.perform(post("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createOrderBody(addressId)))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }
        List<Future<Integer>> results = pool.invokeAll(tasks);
        pool.shutdown();

        long successCount = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 201) {
                successCount++;
            }
        }
        // Exactly 3 of the 5 concurrent attempts can succeed - one unit of stock per order.
        assertThat(successCount).isEqualTo(3);

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity())
                .as("reserved must never exceed stock even under concurrent checkout")
                .isEqualTo(3);
        assertThat(variant.getReservedQuantity()).isLessThanOrEqualTo(variant.getStockQuantity());
    }

    // ===== customer cancel: PENDING_CONFIRMATION -> CANCELLED releases reserved stock =====

    @Test
    void customerCancel_ownOrder_atPendingConfirmation_succeeds_releasesReservedStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Scarf");
        String variantId = createVariant(ctx, productId, 60000, 10);

        String buyerEmail = uniqueEmail("order-cancel-self");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 4);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        var afterCreate = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterCreate.getReservedQuantity()).isEqualTo(4);

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelResult.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CANCELLED");

        var afterCancel = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterCancel.getReservedQuantity()).isEqualTo(0);
        assertThat(afterCancel.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void customerCancel_withoutBody_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Beanie");
        String variantId = createVariant(ctx, productId, 25000, 10);

        String buyerEmail = uniqueEmail("order-cancel-nobody");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk());
    }

    // ===== customer cannot cancel another user's order =====

    @Test
    void customerCancel_anotherUsersOrder_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Wallet");
        String variantId = createVariant(ctx, productId, 70000, 10);

        String ownerEmail = uniqueEmail("order-cancel-owner");
        TokenPair owner = registerUser(ownerEmail);
        addToCart(owner.accessToken(), variantId, 1);
        String addressId = createAddressForUser(ownerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        String strangerEmail = uniqueEmail("order-cancel-stranger");
        TokenPair stranger = registerUser(strangerEmail);
        MvcResult cancelResult = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not mine\"}"))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(404);

        // Owner's order must be untouched by the stranger's rejected attempt.
        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity()).isEqualTo(1);
    }

    // ===== confirmed cancellation is immediate and restores stock =====

    @Test
    void customerCancel_afterConfirmed_cancelsImmediately_andRestoresStock() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Backpack");
        String variantId = createVariant(ctx, productId, 150000, 10);

        String buyerEmail = uniqueEmail("order-cancel-confirmed");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 2);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());

        var afterConfirm = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterConfirm.getStockQuantity()).isEqualTo(8);
        assertThat(afterConfirm.getReservedQuantity()).isEqualTo(0);

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelResult.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CANCELLED");

        var afterCancelAttempt = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(afterCancelAttempt.getStockQuantity()).isEqualTo(10);
        assertThat(afterCancelAttempt.getReservedQuantity()).isEqualTo(0);

        MvcResult detail = mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CANCELLED");
    }

    // ===== without a carrier integration, SHIPPING can still be cancelled =====

    @Test
    void customerCancel_atShipping_cancelsImmediately() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Sunglasses");
        String variantId = createVariant(ctx, productId, 90000, 10);

        String buyerEmail = uniqueEmail("order-cancel-shipping");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        for (String nextStatus : new String[] {"CONFIRMED", "PACKING", "SHIPPING"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + nextStatus + "\"}"))
                    .andExpect(status().isOk());
        }

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andReturn();
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelResult.getResponse().getContentAsString()).at("/data/orderStatus").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void deliveredCodOrder_isMarkedPaid() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "COD Payment Completion");
        String variantId = createVariant(ctx, productId, 110000, 10);
        String buyerEmail = uniqueEmail("cod-paid-on-delivery");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(buyerEmail);

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = json(created.getResponse().getContentAsString()).at("/data/id").asText();

        for (String nextStatus : new String[] {"CONFIRMED", "PACKING", "SHIPPING", "DELIVERED"}) {
            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + nextStatus + "\"}"))
                    .andExpect(status().isOk());
        }

        MvcResult detail = mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(detail.getResponse().getContentAsString()).at("/data/paymentStatus").asText())
                .isEqualTo("PAID");
    }

    // ===== double-submit: same cart, two concurrent checkout requests =====

    @Test
    void doubleSubmit_sameCartConcurrentCheckout_onlyOneOrderSucceeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Raincoat");
        String variantId = createVariant(ctx, productId, 120000, 10);

        String buyerEmail = uniqueEmail("order-double-submit");
        TokenPair buyer = registerUser(buyerEmail);
        addToCart(buyer.accessToken(), variantId, 2);
        String addressId = createAddressForUser(buyerEmail);
        String body = createOrderBody(addressId);

        Callable<Integer> checkoutCall = () -> mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = pool.invokeAll(List.of(checkoutCall, checkoutCall));
        pool.shutdown();

        int successCount = 0;
        for (Future<Integer> f : results) {
            int statusCode = f.get();
            assertThat(statusCode).as("loser must fail in a controlled way, never 500").isIn(201, 422);
            if (statusCode == 201) {
                successCount++;
            }
        }
        assertThat(successCount).as("exactly one of the two concurrent checkouts must succeed").isEqualTo(1);

        var userId = userRepository.findByEmail(buyerEmail).orElseThrow().getId();
        long ordersForUser = orderRepository.findAll().stream()
                .filter(o -> o.getUser().getId().equals(userId))
                .count();
        assertThat(ordersForUser).as("exactly one order must exist for this user").isEqualTo(1);

        var cart = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId()))
                .as("cart must be cleared exactly once, not left over from a partially-applied second attempt")
                .isEmpty();

        var variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertThat(variant.getReservedQuantity())
                .as("reserved stock must reflect exactly one order's worth, not doubled")
                .isEqualTo(2);
    }
}
