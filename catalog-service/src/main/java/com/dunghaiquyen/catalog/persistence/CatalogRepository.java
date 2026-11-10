package com.dunghaiquyen.catalog.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

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
        return jdbc.sql("select id,name,slug,status,description,version from products where id=:id")
                .param("id", id).query().listOfRows().stream().findFirst();
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
