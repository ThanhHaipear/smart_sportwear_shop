package com.dunghaiquyen.ecommerce.modules.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartIdentityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CartIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private UserRepository userRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("cart-admin"));
        String categorySlug = "cart-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "cart-brand-" + UUID.randomUUID();
        MvcResult brand = mockMvc.perform(post("/api/v1/admin/brands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Brand\",\"slug\":\"" + brandSlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String brandId = json(brand.getResponse().getContentAsString()).at("/data/id").asText();

        return new AdminContext(token, categoryId, brandId);
    }

    private String createActiveProduct(AdminContext ctx) throws Exception {
        String slug = "cart-prod-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Shirt\",\"slug\":\"%s\",\"categoryId\":\"%s\",\"brandId\":\"%s\",\"status\":\"DRAFT\"}")
                                .formatted(slug, ctx.categoryId(), ctx.brandId())))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createVariant(AdminContext ctx, String productId, int stockQuantity) throws Exception {
        return createVariant(ctx, productId, stockQuantity, "M");
    }

    private String createVariant(AdminContext ctx, String productId, int stockQuantity, String size) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/products/" + productId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sku\":\"SKU-%s\",\"size\":\"%s\",\"color\":\"Black\",\"price\":100000,\"stockQuantity\":%d}")
                                .formatted(UUID.randomUUID(), size, stockQuantity)))
                .andExpect(status().isCreated())
                .andReturn();
        String variantId = json(result.getResponse().getContentAsString()).at("/data/id").asText();
        makeProductPublishableForTest(productId);
        return variantId;
    }

    private Cookie sessionCookie(String value) {
        return new Cookie(CartIdentityResolver.SESSION_COOKIE_NAME, value);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, String sessionId, String bearerToken) throws Exception {
        if (sessionId != null) {
            builder = builder.cookie(sessionCookie(sessionId));
        }
        if (bearerToken != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private String sessionIdOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(CartIdentityResolver.SESSION_COOKIE_NAME);
        return cookie != null ? cookie.getValue() : null;
    }

    private String addItemBody(String variantId, int quantity) {
        return "{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}";
    }

    // ===== A. guest get cart when empty =====

    @Test
    void guest_getCart_whenEmpty_createsAndReturnsEmptyCart() throws Exception {
        MvcResult result = perform(get("/api/v1/cart"), null, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(sessionIdOf(result)).isNotBlank();
        JsonNode body = json(result.getResponse().getContentAsString());
        assertThat(body.at("/data/id").asText()).isNotBlank();
        assertThat(body.at("/data/items")).isEmpty();
        assertThat(body.at("/data/subtotal").asDouble()).isEqualTo(0.0);
    }

    // ===== B/C. guest add item, add same variant again merges =====

    @Test
    void guest_addItem_thenAddSameVariantAgain_mergesQuantity_doesNotDuplicateRow() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult first = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String sessionId = sessionIdOf(first);
        JsonNode firstBody = json(first.getResponse().getContentAsString());
        assertThat(firstBody.at("/data/items")).hasSize(1);
        assertThat(firstBody.at("/data/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(firstBody.at("/data/items/0/thumbnail").asText())
                .startsWith("https://res.cloudinary.com/test/image/upload/");
        String itemId = firstBody.at("/data/items/0/id").asText();

        MvcResult second = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 3)),
                sessionId, null);
        JsonNode secondBody = json(second.getResponse().getContentAsString());
        assertThat(secondBody.at("/data/items")).hasSize(1);
        assertThat(secondBody.at("/data/items/0/id").asText()).isEqualTo(itemId);
        assertThat(secondBody.at("/data/items/0/quantity").asInt()).isEqualTo(5);
        assertThat(secondBody.at("/data/subtotal").asDouble()).isEqualTo(500000.0);
    }

    @Test
    void guest_addNewItem_displaysNewestItemFirst() throws Exception {
        AdminContext ctx = setUpAdmin();
        String firstProductId = createActiveProduct(ctx);
        String firstVariantId = createVariant(ctx, firstProductId, 10);
        String secondProductId = createActiveProduct(ctx);
        String secondVariantId = createVariant(ctx, secondProductId, 10);

        MvcResult first = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(firstVariantId, 1)),
                null, null);
        String sessionId = sessionIdOf(first);

        MvcResult second = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(secondVariantId, 1)),
                sessionId, null);

        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode items = json(second.getResponse().getContentAsString()).at("/data/items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("variantId").asText()).isEqualTo(secondVariantId);
        assertThat(items.get(1).get("variantId").asText()).isEqualTo(firstVariantId);
    }

    // ===== D. guest update quantity =====

    @Test
    void guest_updateQuantity_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String sessionId = sessionIdOf(added);
        String itemId = json(added.getResponse().getContentAsString()).at("/data/items/0/id").asText();

        MvcResult updated = perform(
                patch("/api/v1/cart/items/" + itemId).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":7}"),
                sessionId, null);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(updated.getResponse().getContentAsString());
        assertThat(body.at("/data/items/0/quantity").asInt()).isEqualTo(7);
        assertThat(body.at("/data/items/0/lineTotal").asDouble()).isEqualTo(700000.0);
    }

    // ===== E. guest delete item =====

    @Test
    void guest_deleteItem_succeeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String sessionId = sessionIdOf(added);
        String itemId = json(added.getResponse().getContentAsString()).at("/data/items/0/id").asText();

        MvcResult deleted = perform(delete("/api/v1/cart/items/" + itemId), sessionId, null);
        assertThat(deleted.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(deleted.getResponse().getContentAsString());
        assertThat(body.at("/data/items")).isEmpty();
    }

    // ===== F. guest cannot modify/delete another session's item =====

    @Test
    void guest_cannotPatchOrDeleteAnotherSessionsItem() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String ownerSessionId = sessionIdOf(added);
        String itemId = json(added.getResponse().getContentAsString()).at("/data/items/0/id").asText();
        assertThat(ownerSessionId).isNotBlank();

        // A different guest session (deliberately NOT reusing ownerSessionId).
        String strangerSessionId = UUID.randomUUID().toString();

        MvcResult patchResult = perform(
                patch("/api/v1/cart/items/" + itemId).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":99}"),
                strangerSessionId, null);
        assertThat(patchResult.getResponse().getStatus()).isEqualTo(404);

        MvcResult deleteResult = perform(delete("/api/v1/cart/items/" + itemId), strangerSessionId, null);
        assertThat(deleteResult.getResponse().getStatus()).isEqualTo(404);

        // The owner's item must be untouched.
        MvcResult ownerCart = perform(get("/api/v1/cart"), ownerSessionId, null);
        JsonNode ownerBody = json(ownerCart.getResponse().getContentAsString());
        assertThat(ownerBody.at("/data/items/0/quantity").asInt()).isEqualTo(2);
    }

    // ===== G/H. user cart fetch + add via JWT =====

    @Test
    void user_getCart_andAddItem_viaJwt() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);
        TokenPair user = registerUser(uniqueEmail("cart-user"));

        MvcResult emptyCart = perform(get("/api/v1/cart"), null, user.accessToken());
        assertThat(emptyCart.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(emptyCart.getResponse().getContentAsString()).at("/data/items")).isEmpty();

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 3)),
                null, user.accessToken());
        assertThat(added.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(added.getResponse().getContentAsString());
        assertThat(body.at("/data/items/0/quantity").asInt()).isEqualTo(3);
        assertThat(body.at("/data/items/0/productId").asText()).isEqualTo(productId);
    }

    // ===== I. user cannot touch another user's item =====

    @Test
    void user_cannotPatchOrDeleteAnotherUsersItem() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        TokenPair owner = registerUser(uniqueEmail("cart-owner"));
        TokenPair stranger = registerUser(uniqueEmail("cart-stranger"));

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, owner.accessToken());
        String itemId = json(added.getResponse().getContentAsString()).at("/data/items/0/id").asText();

        MvcResult patchResult = perform(
                patch("/api/v1/cart/items/" + itemId).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50}"),
                null, stranger.accessToken());
        assertThat(patchResult.getResponse().getStatus()).isEqualTo(404);

        MvcResult deleteResult = perform(delete("/api/v1/cart/items/" + itemId), null, stranger.accessToken());
        assertThat(deleteResult.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== PATCH/DELETE on a bogus item id must not create a cart as a side effect =====

    @Test
    void patchBogusItem_whenGuestHasNoCartYet_returns404_withoutCreatingCart() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        assertThat(cartRepository.findBySessionId(sessionId)).isEmpty();

        MvcResult patchResult = perform(
                patch("/api/v1/cart/items/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"),
                sessionId, null);
        assertThat(patchResult.getResponse().getStatus()).isEqualTo(404);
        assertThat(cartRepository.findBySessionId(sessionId))
                .as("PATCH on a bogus item must not have created a cart row")
                .isEmpty();

        MvcResult getResult = perform(get("/api/v1/cart"), sessionId, null);
        assertThat(getResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(cartRepository.findBySessionId(sessionId))
                .as("GET is the one allowed to lazily create the first cart")
                .isPresent();
    }

    @Test
    void deleteBogusItem_whenUserHasNoCartYet_returns404_withoutCreatingCart() throws Exception {
        String email = uniqueEmail("cart-bogus-user");
        TokenPair user = registerUser(email);
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        assertThat(cartRepository.findByUserId(userId)).isEmpty();

        MvcResult deleteResult = perform(delete("/api/v1/cart/items/" + UUID.randomUUID()), null, user.accessToken());
        assertThat(deleteResult.getResponse().getStatus()).isEqualTo(404);
        assertThat(cartRepository.findByUserId(userId))
                .as("DELETE on a bogus item must not have created a cart row")
                .isEmpty();

        MvcResult getResult = perform(get("/api/v1/cart"), null, user.accessToken());
        assertThat(getResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(cartRepository.findByUserId(userId))
                .as("GET is the one allowed to lazily create the first cart")
                .isPresent();
    }

    // ===== J. add over available stock =====

    @Test
    void addItem_overAvailableStock_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 5);

        MvcResult result = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 6)),
                null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Quantity exceeds available stock");
    }

    // ===== K. INACTIVE variant / DRAFT product rejected =====

    @Test
    void addItem_inactiveVariant_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);
        createVariant(ctx, productId, 1, "L");

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        MvcResult result = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 1)),
                null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Variant is not available");
    }

    @Test
    void concurrentAdds_sameUserAndVariant_accumulateWithoutLostUpdate() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 20);
        String email = uniqueEmail("cart-concurrent-add");
        String token = registerUser(email).accessToken();

        // Establish the cart and its first line before racing two increments.
        perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(variantId, 1)),
                null, token);

        Callable<Integer> addTwo = () -> perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(variantId, 2)),
                null, token).getResponse().getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = pool.invokeAll(List.of(addTwo, addTwo));
        pool.shutdown();

        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(201);
        }
        var user = userRepository.findByEmail(email).orElseThrow();
        var cart = cartRepository.findByUserId(user.getId()).orElseThrow();
        var items = cartItemRepository.findAllByCartIdWithVariantAndProduct(cart.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity())
                .as("1 initial + two concurrent increments of 2 must all be retained")
                .isEqualTo(5);
    }

    @Test
    void addItem_outOfStockVariant_returns422_evenWhenPhysicalStockRemains() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_STOCK\"}"))
                .andExpect(status().isOk());

        MvcResult result = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 1)),
                null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Variant is not available");
    }

    @Test
    void addItem_draftProduct_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());

        MvcResult result = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 1)),
                null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result.getResponse().getContentAsString()).at("/message").asText())
                .isEqualTo("Product is not available");
    }

    @Test
    void addItem_unknownVariant_returns404() throws Exception {
        MvcResult result = perform(
                post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(UUID.randomUUID().toString(), 1)),
                null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ===== P. malformed/invalid quantity =====

    @Test
    void addItem_nonPositiveQuantity_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(variantId, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(variantId, -3)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void updateQuantity_zeroOrNegative_returns422_explicitRejectNotImplicitDelete() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String sessionId = sessionIdOf(added);
        String itemId = json(added.getResponse().getContentAsString()).at("/data/items/0/id").asText();

        MvcResult zeroResult = perform(
                patch("/api/v1/cart/items/" + itemId).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"),
                sessionId, null);
        assertThat(zeroResult.getResponse().getStatus()).isEqualTo(422);

        // Item must still exist, untouched, after the rejected PATCH.
        MvcResult cart = perform(get("/api/v1/cart"), sessionId, null);
        JsonNode body = json(cart.getResponse().getContentAsString());
        assertThat(body.at("/data/items")).hasSize(1);
        assertThat(body.at("/data/items/0/quantity").asInt()).isEqualTo(2);
    }

    // ===== L/M/N/O. merge guest cart into user cart on login/register =====

    @Test
    void register_mergesGuestCartIntoNewUserCart_andDeletesGuestCart() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        MvcResult added = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String guestSessionId = sessionIdOf(added);

        String email = uniqueEmail("cart-register-merge");
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .cookie(sessionCookie(guestSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Merge User\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = json(registerResult.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();

        MvcResult userCart = perform(get("/api/v1/cart"), null, accessToken);
        JsonNode userBody = json(userCart.getResponse().getContentAsString());
        assertThat(userBody.at("/data/items")).hasSize(1);
        assertThat(userBody.at("/data/items/0/quantity").asInt()).isEqualTo(2);

        // Guest cart is gone: same old cookie now lazily creates a brand new, empty cart.
        MvcResult staleGuestCart = perform(get("/api/v1/cart"), guestSessionId, null);
        JsonNode staleBody = json(staleGuestCart.getResponse().getContentAsString());
        assertThat(staleBody.at("/data/items")).isEmpty();
        assertThat(staleBody.at("/data/id").asText()).isNotEqualTo(userBody.at("/data/id").asText());
    }

    @Test
    void login_mergesGuestCartIntoExistingUserCart_accumulatingQuantityForSameVariant() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 20);

        // registerUser() always uses password "Password123" - reused here so this test can log in below.
        String email = uniqueEmail("cart-login-merge");
        TokenPair user = registerUser(email);
        // user already has 4 of this variant in their own cart
        perform(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 4)),
                null, user.accessToken());

        // a guest session adds 3 more of the SAME variant
        MvcResult guestAdded = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 3)),
                null, null);
        String guestSessionId = sessionIdOf(guestAdded);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(sessionCookie(guestSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = json(loginResult.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();

        MvcResult cart = perform(get("/api/v1/cart"), null, accessToken);
        JsonNode body = json(cart.getResponse().getContentAsString());
        assertThat(body.at("/data/items")).hasSize(1);
        assertThat(body.at("/data/items/0/quantity").asInt()).isEqualTo(7);
    }

    // ===== merge must drop items no longer valid at merge time =====

    @Test
    void register_merge_dropsItemWhoseVariantWentInactive_authStillSucceeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);
        createVariant(ctx, productId, 1, "L");

        MvcResult guestAdded = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String guestSessionId = sessionIdOf(guestAdded);

        // Variant becomes INACTIVE after it was added to the guest cart, before merge runs.
        mockMvc.perform(patch("/api/v1/admin/variants/" + variantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        String email = uniqueEmail("cart-merge-drop-variant");
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .cookie(sessionCookie(guestSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Drop User\",\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken =
                json(registerResult.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();

        MvcResult userCart = perform(get("/api/v1/cart"), null, accessToken);
        JsonNode body = json(userCart.getResponse().getContentAsString());
        assertThat(body.at("/data/items"))
                .as("the now-INACTIVE variant's item must not have been carried into the user cart")
                .isEmpty();
    }

    @Test
    void login_merge_dropsItemWhoseProductWentDraft_authStillSucceeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        String email = uniqueEmail("cart-merge-drop-product");
        registerUser(email);

        MvcResult guestAdded = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 2)),
                null, null);
        String guestSessionId = sessionIdOf(guestAdded);

        // Product becomes DRAFT after the item was added to the guest cart, before merge runs.
        mockMvc.perform(patch("/api/v1/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(sessionCookie(guestSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = json(loginResult.getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();

        MvcResult userCart = perform(get("/api/v1/cart"), null, accessToken);
        JsonNode body = json(userCart.getResponse().getContentAsString());
        assertThat(body.at("/data/items"))
                .as("the now-DRAFT product's item must not have been carried into the user cart")
                .isEmpty();
    }

    // ===== Q. merge over-stock: clamp to available, never fail login/register =====

    @Test
    void mergeOverStock_clampsToAvailableStock_andLoginStillSucceeds() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx);
        String variantId = createVariant(ctx, productId, 10);

        String email = uniqueEmail("cart-merge-clamp");
        TokenPair user = registerUser(email);
        String accessToken = user.accessToken();

        // user already has 7 of the 10 available
        perform(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 7)),
                null, accessToken);

        // guest session adds 8 more of the same variant (7 + 8 = 15 > 10 available)
        MvcResult guestAdded = perform(
                post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemBody(variantId, 8)),
                null, null);
        String guestSessionId = sessionIdOf(guestAdded);

        // Logging in must still succeed (200), not fail just because the merge would overflow stock.
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(sessionCookie(guestSessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult cart = perform(get("/api/v1/cart"), null, accessToken);
        JsonNode body = json(cart.getResponse().getContentAsString());
        assertThat(body.at("/data/items")).hasSize(1);
        // Clamped to available stock (10), not 15.
        assertThat(body.at("/data/items/0/quantity").asInt()).isEqualTo(10);
    }
}
