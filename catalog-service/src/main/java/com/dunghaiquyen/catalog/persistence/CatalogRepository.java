package com.dunghaiquyen.catalog.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public CatalogRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record SaleSnapshot(UUID variantId, UUID productId, String productName, String sku, String size,
                               String color, BigDecimal unitPrice, String productStatus, String variantStatus,
                               int availableQuantity, long version) {}

    public Optional<SaleSnapshot> findSaleSnapshot(UUID id) {
        return jdbc.sql("""
                select v.id variant_id,p.id product_id,p.name product_name,v.sku,v.size_label,v.color_label,
                       v.price,p.status product_status,v.status variant_status,
                       v.stock_quantity-v.reserved_quantity available_quantity,v.version
                  from product_variants v join products p on p.id=v.product_id where v.id=:id
                """).param("id", id).query((rs, n) -> new SaleSnapshot(
                rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_name"), rs.getString("sku"), rs.getString("size_label"),
                rs.getString("color_label"), rs.getBigDecimal("price"), rs.getString("product_status"),
                rs.getString("variant_status"), rs.getInt("available_quantity"), rs.getLong("version"))).optional();
    }

    public Optional<Map<String, Object>> findIndexDocument(UUID id) {
        Optional<Map<String, Object>> result = jdbc.sql("""
                select p.id,p.name,p.status,p.description,p.version,p.attributes,
                       c.id category_id,c.name category_name,c.slug category_slug,
                       b.id brand_id,b.name brand_name,b.slug brand_slug
                  from products p
                  left join categories c on c.id=p.category_id
                  left join brands b on b.id=p.brand_id
                 where p.id=:id
                """).param("id", id).query((rs, row) -> {
                    Map<String, Object> attributes = attributes(rs.getObject("attributes"));
                    return map(
                            "productId", rs.getObject("id", UUID.class),
                            "sourceVersion", rs.getLong("version"),
                            "status", rs.getString("status"),
                            "name", rs.getString("name"),
                            "description", nullToEmpty(rs.getString("description")),
                            "categoryId", rs.getObject("category_id", UUID.class),
                            "category", rs.getString("category_name"),
                            "categorySlug", rs.getString("category_slug"),
                            "brandId", rs.getObject("brand_id", UUID.class),
                            "brand", rs.getString("brand_name"),
                            "brandSlug", rs.getString("brand_slug"),
                            "productType", attributes.get("productType"),
                            "gender", attributes.get("gender"),
                            "sportCodes", sportCodes(attributes),
                            "attributes", attributes);
                }).optional();
        result.ifPresent(document -> {
            document.put("variants", indexVariants(id));
            document.put("images", indexImages(id));
        });
        return result;
    }

    private List<Map<String, Object>> indexVariants(UUID productId) {
        return jdbc.sql("""
                select id,sku,size_label,color_label,price,
                       stock_quantity-reserved_quantity available_quantity,status
                  from product_variants where product_id=:id order by id
                """).param("id", productId).query((rs, row) -> map(
                        "id", rs.getObject("id", UUID.class),
                        "sku", rs.getString("sku"),
                        "size", rs.getString("size_label"),
                        "color", rs.getString("color_label"),
                        "colorFamily", null,
                        "price", rs.getBigDecimal("price"),
                        "availableQuantity", rs.getInt("available_quantity"),
                        "status", rs.getString("status"))).list();
    }

    private List<Map<String, Object>> indexImages(UUID productId) {
        return jdbc.sql("""
                select id,secure_url,sort_order,public_id,asset_version,width,height,asset_format,
                       asset_bytes,checksum,source_kind,license_code,source_uri,source_group_code
                  from product_images
                 where product_id=:id order by sort_order,id
                """).param("id", productId).query((rs, row) -> map(
                        "id", rs.getObject("id", UUID.class),
                        "url", rs.getString("secure_url"),
                        "publicId", rs.getString("public_id"),
                        "assetVersion", rs.getString("asset_version"),
                        "width", rs.getObject("width", Integer.class),
                        "height", rs.getObject("height", Integer.class),
                        "format", rs.getString("asset_format"),
                        "bytes", rs.getObject("asset_bytes", Long.class),
                        "checksum", rs.getString("checksum"),
                        "sourceKind", rs.getString("source_kind"),
                        "licenseCode", rs.getString("license_code"),
                        "sourceUri", rs.getString("source_uri"),
                        "sourceGroupCode", rs.getString("source_group_code"),
                        "sortOrder", rs.getInt("sort_order"))).list();
    }

    private Map<String, Object> attributes(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        try { return new LinkedHashMap<>(json.readValue(String.valueOf(raw), JSON_MAP)); }
        catch (Exception error) { throw new IllegalStateException("Invalid product attributes JSON", error); }
    }

    private static List<String> sportCodes(Map<String, Object> attributes) {
        Object explicit = attributes.get("sportCodes");
        if (explicit instanceof Collection<?> values) {
            return values.stream().filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf).toList();
        }
        Object sportType = attributes.get("sportType");
        return sportType == null || String.valueOf(sportType).isBlank()
                ? List.of() : List.of(String.valueOf(sportType));
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    public List<LockedVariant> lockVariants(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                select v.id,v.stock_quantity,v.reserved_quantity,v.status,p.status product_status
                  from product_variants v join products p on p.id=v.product_id
                 where v.id in (:ids) order by v.id for update
                """).param("ids", ids).query((rs, n) -> new LockedVariant(rs.getObject("id", UUID.class),
                rs.getInt("stock_quantity"), rs.getInt("reserved_quantity"), rs.getString("status"),
                rs.getString("product_status"))).list();
    }

    public record LockedVariant(UUID id, int stock, int reserved, String status, String productStatus) {}
    public void addReserved(UUID variantId, int quantity) {
        jdbc.sql("update product_variants set reserved_quantity=reserved_quantity+:q,version=version+1,updated_at=current_timestamp where id=:id")
                .param("q", quantity).param("id", variantId).update();
    }
    public void confirm(UUID variantId, int quantity) {
        jdbc.sql("update product_variants set stock_quantity=stock_quantity-:q,reserved_quantity=reserved_quantity-:q,version=version+1,updated_at=current_timestamp where id=:id")
                .param("q", quantity).param("id", variantId).update();
    }
    public void release(UUID variantId, int quantity) {
        jdbc.sql("update product_variants set reserved_quantity=reserved_quantity-:q,version=version+1,updated_at=current_timestamp where id=:id")
                .param("q", quantity).param("id", variantId).update();
    }
}
