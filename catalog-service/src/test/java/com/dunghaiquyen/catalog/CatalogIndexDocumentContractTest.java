package com.dunghaiquyen.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CatalogIndexDocumentContractTest {
    private static final UUID PRODUCT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VARIANT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CATEGORY = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID BRAND = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID IMAGE = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test void catalogResponseMatchesSharedSearchContract() throws Exception {
        jdbc.sql("insert into categories(id,name,slug) values(:id,'Giày bóng đá','giay-bong-da')")
                .param("id", CATEGORY).update();
        jdbc.sql("insert into brands(id,name,slug) values(:id,'Demo','demo')")
                .param("id", BRAND).update();
        jdbc.sql("""
                insert into products(id,category_id,brand_id,name,slug,status,description,attributes,version)
                values(:id,:category,:brand,'Giày bóng đá Demo','giay-bong-da-demo','ACTIVE',
                       'Giày sân cỏ nhân tạo',:attributes,2)
                """).param("id", PRODUCT).param("category", CATEGORY).param("brand", BRAND)
                .param("attributes", "{\"gender\":\"UNISEX\",\"productType\":\"SPORTS_SHOES\",\"sportType\":\"FOOTBALL\"}")
                .update();
        jdbc.sql("""
                insert into product_variants(id,product_id,sku,size_label,color_label,price,
                    stock_quantity,reserved_quantity,status,version,updated_at)
                values(:id,:product,'DEMO-42-BLACK','42','Black',1299000,9,2,'ACTIVE',1,current_timestamp)
                """).param("id", VARIANT).param("product", PRODUCT).update();
        jdbc.sql("""
                insert into product_images(id,product_id,secure_url,sort_order,is_primary,public_id,asset_version,
                    width,height,asset_format,asset_bytes,checksum,source_kind,license_code,source_uri,source_group_code)
                values(:id,:product,'https://res.cloudinary.com/demo/image/upload/v1/shoe.jpg',0,true,
                    'shoe','1',224,224,'jpg',1024,:checksum,'SYNTHETIC','AI_GENERATED_PROJECT_USE',
                    'catalog://demo/shoe.jpg','DEMO-SHOE-1')
                """).param("id", IMAGE).param("product", PRODUCT).param("checksum", "a".repeat(64)).update();

        Path fixture = Path.of("..", "contracts", "examples", "catalog-index-document-v1.json").normalize();
        String expected = Files.readString(fixture);
        mvc.perform(get("/internal/v1/products/{id}/index-document", PRODUCT)
                        .header("X-Internal-Service-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, true));
    }
}
