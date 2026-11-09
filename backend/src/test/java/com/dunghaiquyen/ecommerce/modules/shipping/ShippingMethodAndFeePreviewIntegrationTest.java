package com.dunghaiquyen.ecommerce.modules.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dunghaiquyen.ecommerce.AbstractIntegrationTest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethod;
import com.dunghaiquyen.ecommerce.modules.shipping.entity.ShippingMethodStatus;
import com.dunghaiquyen.ecommerce.modules.shipping.repository.ShippingMethodRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Tag("phase0-excluded")
@Tag("dormant")
class ShippingMethodAndFeePreviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ShippingMethodRepository shippingMethodRepository;

    @Autowired
    private AddressRepository addressRepository;

    private record AdminContext(String token, String categoryId, String brandId) {
    }

    private AdminContext setUpAdmin() throws Exception {
        String token = registerAdminAndGetAccessToken(uniqueEmail("ship-admin"));
        String categorySlug = "ship-cat-" + UUID.randomUUID();
        MvcResult cat = mockMvc.perform(post("/api/v1/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"slug\":\"" + categorySlug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = json(cat.getResponse().getContentAsString()).at("/data/id").asText();

        String brandSlug = "ship-brand-" + UUID.randomUUID();
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
        String slug = "ship-prod-" + UUID.randomUUID();
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

    private ShippingMethod createMethod(String name, String code, int baseFee, ShippingMethodStatus status,
            Integer etaMin, Integer etaMax) {
        ShippingMethod method = new ShippingMethod();
        method.setName(name);
        method.setCode(code + "-" + UUID.randomUUID());
        method.setProvider("GHN");
        method.setBaseFee(BigDecimal.valueOf(baseFee));
        method.setStatus(status);
        method.setEstimatedDaysMin(etaMin);
        method.setEstimatedDaysMax(etaMax);
        return shippingMethodRepository.save(method);
    }

    // ===== public methods list =====

    @Test
    void methods_returnsOnlyActiveMethods_noAuthRequired() throws Exception {
        ShippingMethod active = createMethod("Standard", "STD", 20000, ShippingMethodStatus.ACTIVE, 2, 4);
        ShippingMethod inactive = createMethod("Discontinued", "OLD", 10000, ShippingMethodStatus.INACTIVE, 1, 2);

        MvcResult result = mockMvc.perform(get("/api/v1/shipping/methods"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = json(result.getResponse().getContentAsString()).at("/data");

        boolean foundActive = false;
        boolean foundInactive = false;
        for (JsonNode item : data) {
            if (item.at("/id").asText().equals(active.getId().toString())) {
                foundActive = true;
                assertThat(item.at("/name").asText()).isEqualTo("Standard");
                assertThat(item.at("/baseFee").asDouble()).isEqualTo(20000.0);
                assertThat(item.at("/estimatedDaysMin").asInt()).isEqualTo(2);
                assertThat(item.at("/estimatedDaysMax").asInt()).isEqualTo(4);
            }
            if (item.at("/id").asText().equals(inactive.getId().toString())) {
                foundInactive = true;
            }
        }
        assertThat(foundActive).isTrue();
        assertThat(foundInactive).as("INACTIVE methods must not be offered to customers").isFalse();
    }

    // ===== fee preview =====

    @Test
    void feePreview_validCartAndMethod_returnsShippingFeeFromOrderServiceRule() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Cap");
        String variantId = createVariant(ctx, productId, 100000, 10);
        ShippingMethod method = createMethod("Standard", "STD", 25000, ShippingMethodStatus.ACTIVE, 2, 4);

        String email = uniqueEmail("ship-preview-buyer");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 2);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"shippingMethodId\":\""
                                + method.getId() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());

        assertThat(body.at("/data/subtotal").asDouble()).isEqualTo(200000.0);
        // Default test config (app.shipping.flat-fee=0) - same number createOrderFromCart would charge.
        assertThat(body.at("/data/shippingFee").asDouble()).isEqualTo(0.0);
        assertThat(body.at("/data/shippingMethod/id").asText()).isEqualTo(method.getId().toString());
        assertThat(body.at("/data/shippingMethod/baseFee").asDouble()).isEqualTo(25000.0);
        assertThat(body.at("/data/estimatedDeliveryDateFrom").asText()).isNotBlank();
        assertThat(body.at("/data/estimatedDeliveryDateTo").asText()).isNotBlank();
    }

    @Test
    void feePreview_emptyCart_returns422() throws Exception {
        ShippingMethod method = createMethod("Standard", "STD2", 25000, ShippingMethodStatus.ACTIVE, 2, 4);
        String email = uniqueEmail("ship-preview-empty");
        TokenPair buyer = registerUser(email);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"shippingMethodId\":\""
                                + method.getId() + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void feePreview_addressNotOwnedByCaller_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Hat");
        String variantId = createVariant(ctx, productId, 50000, 10);
        ShippingMethod method = createMethod("Standard", "STD3", 25000, ShippingMethodStatus.ACTIVE, 2, 4);

        String ownerEmail = uniqueEmail("ship-preview-owner");
        registerUser(ownerEmail);
        String addressId = createAddressForUser(ownerEmail);

        String strangerEmail = uniqueEmail("ship-preview-stranger");
        TokenPair stranger = registerUser(strangerEmail);
        addToCart(stranger.accessToken(), variantId, 1);

        MvcResult result = mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"shippingMethodId\":\""
                                + method.getId() + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void feePreview_inactiveMethod_returns422() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Gloves");
        String variantId = createVariant(ctx, productId, 50000, 10);
        ShippingMethod method = createMethod("Discontinued", "OLD2", 10000, ShippingMethodStatus.INACTIVE, 1, 2);

        String email = uniqueEmail("ship-prev-inactive");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"shippingMethodId\":\""
                                + method.getId() + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void feePreview_methodNotFound_returns404() throws Exception {
        AdminContext ctx = setUpAdmin();
        String productId = createActiveProduct(ctx, "Socks");
        String variantId = createVariant(ctx, productId, 50000, 10);

        String email = uniqueEmail("ship-preview-no-method");
        TokenPair buyer = registerUser(email);
        addToCart(buyer.accessToken(), variantId, 1);
        String addressId = createAddressForUser(email);

        MvcResult result = mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + addressId + "\",\"shippingMethodId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void feePreview_unauthenticated_returns401() throws Exception {
        ShippingMethod method = createMethod("Standard", "STD4", 25000, ShippingMethodStatus.ACTIVE, 2, 4);
        mockMvc.perform(post("/api/v1/shipping/fee-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + UUID.randomUUID() + "\",\"shippingMethodId\":\""
                                + method.getId() + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
