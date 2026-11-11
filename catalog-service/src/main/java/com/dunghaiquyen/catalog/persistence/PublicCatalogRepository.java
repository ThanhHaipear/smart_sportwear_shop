package com.dunghaiquyen.catalog.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PublicCatalogRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PublicCatalogRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> categories() {
        return jdbc.sql("""
                select c.id,c.name,c.slug,c.description,c.status,c.parent_id,p.name parent_name,
                       p.slug parent_slug,c.sort_order
                  from categories c left join categories p on p.id=c.parent_id
                 where c.status='ACTIVE' order by c.sort_order,c.name
                """).query((rs, row) -> category(rs)).list();
    }

    public Optional<Map<String, Object>> category(String slugOrId) {
        return jdbc.sql("""
                select c.id,c.name,c.slug,c.description,c.status,c.parent_id,p.name parent_name,
                       p.slug parent_slug,c.sort_order
                  from categories c left join categories p on p.id=c.parent_id
                 where c.status='ACTIVE' and (c.slug=:value or cast(c.id as text)=:value)
                """).param("value", slugOrId).query((rs, row) -> category(rs)).optional();
    }

    public List<Map<String, Object>> brands() {
        return jdbc.sql("""
                select id,name,slug,description,status from brands
                 where status='ACTIVE' order by name
                """).query((rs, row) -> map(
                "id", uuid(rs, "id"), "name", rs.getString("name"), "slug", rs.getString("slug"),
                "description", rs.getString("description"), "status", rs.getString("status"))).list();
    }

    public List<Map<String, Object>> activePromotions() {
        return jdbc.sql("""
                select id,name,slug,description,type,scope,discount_percent,discount_amount,
                       max_discount_amount,min_order_amount,starts_at,ends_at,status
                  from promotions
                 where status='ACTIVE' and (starts_at is null or starts_at<=current_timestamp)
                   and (ends_at is null or ends_at>=current_timestamp)
                 order by starts_at desc nulls last,name
                """).query().listOfRows();
    }

    public List<Map<String, Object>> suggestions(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) return List.of();
        return jdbc.sql("""
                select p.id,p.name,p.slug,
                       (select i.secure_url from product_images i where i.product_id=p.id
                         order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail
                  from products p where p.status='ACTIVE' and lower(p.name) like lower(:query)
                 order by case when lower(p.name) like lower(:prefix) then 0 else 1 end,p.name limit 8
                """).param("query", "%" + normalized + "%").param("prefix", normalized + "%")
                .query((rs, row) -> map("id", uuid(rs, "id"), "name", rs.getString("name"),
                        "slug", rs.getString("slug"), "thumbnail", rs.getString("thumbnail"))).list();
    }

    public long productCount(String query, String categorySlug, String brandSlug) {
        return jdbc.sql("""
                select count(*) from products p
                  left join categories c on c.id=p.category_id left join brands b on b.id=p.brand_id
                 where p.status='ACTIVE'
                   and (:query='' or lower(p.name) like lower(:queryLike) or lower(coalesce(p.description,'')) like lower(:queryLike))
                   and (:category='' or c.slug=:category) and (:brand='' or b.slug=:brand)
                """).param("query", clean(query)).param("queryLike", "%" + clean(query) + "%")
                .param("category", clean(categorySlug)).param("brand", clean(brandSlug))
                .query(Long.class).single();
    }

    public List<Map<String, Object>> products(String query, String categorySlug, String brandSlug,
                                               int limit, int offset) {
        return jdbc.sql("""
                select p.id,p.name,p.slug,p.description,p.status,p.attributes,
                       b.id brand_id,b.name brand_name,c.id category_id,c.name category_name,
                       (select i.secure_url from product_images i where i.product_id=p.id
                         order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail,
                       (select min(v.price) from product_variants v where v.product_id=p.id and v.status='ACTIVE') min_price,
                       (select max(v.price) from product_variants v where v.product_id=p.id and v.status='ACTIVE') max_price,
                       (select min(v.price) from product_variants v where v.product_id=p.id and v.status='ACTIVE') sale_price,
                       (select max(v.compare_at_price) from product_variants v where v.product_id=p.id and v.status='ACTIVE') compare_at_price,
                       (select coalesce(sum(v.stock_quantity-v.reserved_quantity),0) from product_variants v
                         where v.product_id=p.id and v.status='ACTIVE') available_quantity
                  from products p left join categories c on c.id=p.category_id left join brands b on b.id=p.brand_id
                 where p.status='ACTIVE'
                   and (:query='' or lower(p.name) like lower(:queryLike) or lower(coalesce(p.description,'')) like lower(:queryLike))
                   and (:category='' or c.slug=:category) and (:brand='' or b.slug=:brand)
                 order by p.updated_at desc,p.id limit :limit offset :offset
                """).param("query", clean(query)).param("queryLike", "%" + clean(query) + "%")
                .param("category", clean(categorySlug)).param("brand", clean(brandSlug))
                .param("limit", limit).param("offset", offset)
                .query((rs, row) -> productListItem(rs)).list();
    }

    public Optional<Map<String, Object>> product(String slugOrId) {
        return product(slugOrId,true);
    }
    public Optional<Map<String,Object>> adminProduct(String slugOrId){return product(slugOrId,false);}
    private Optional<Map<String,Object>> product(String slugOrId,boolean activeOnly) {
        Optional<Map<String, Object>> product = jdbc.sql("""
                select p.id,p.name,p.slug,p.description,p.status,p.attributes,
                       b.id brand_id,b.name brand_name,c.id category_id,c.name category_name
                  from products p left join categories c on c.id=p.category_id left join brands b on b.id=p.brand_id
                 where (:active=false or p.status='ACTIVE') and (p.slug=:value or cast(p.id as text)=:value)
                """).param("active",activeOnly).param("value", slugOrId).query((rs, row) -> {
                    Map<String, Object> result = map(
                            "id", uuid(rs, "id"), "name", rs.getString("name"), "slug", rs.getString("slug"),
                            "shortDescription", shortDescription(rs.getString("description")),
                            "description", rs.getString("description"), "gender", attribute(rs, "gender"),
                            "sportType", attribute(rs, "sportType"), "productType", attribute(rs, "productType"),
                            "brand", reference(rs, "brand_id", "brand_name"),
                            "category", reference(rs, "category_id", "category_name"),
                            "status", rs.getString("status"), "isFeatured", booleanAttribute(rs, "isFeatured"),
                            "attributes", attributes(rs));
                    return result;
                }).optional();
        product.ifPresent(value -> {
            UUID id = (UUID) value.get("id");
            value.put("images", images(id));
            value.put("variants", variants(id));
            value.put("reviewSummary", reviewSummary(id));
            value.put("relatedProducts", List.of());
        });
        return product;
    }

    @Transactional public Map<String,Object> createCategory(Map<String,Object> body){
        UUID id=UUID.randomUUID();String slug=String.valueOf(body.get("slug"));jdbc.sql("""
                insert into categories(id,parent_id,code,name,slug,description,status,sort_order)
                values(:id,:parent,:code,:name,:slug,:description,:status,:sort)
                """).param("id",id).param("parent",uuidValue(body.get("parentId"))).param("code",code(slug))
                .param("name",body.get("name")).param("slug",slug).param("description",body.get("description"))
                .param("status",body.getOrDefault("status","ACTIVE")).param("sort",number(body.get("sortOrder"),0)).update();
        return category(String.valueOf(id)).orElseThrow();
    }
    @Transactional public Map<String,Object> updateCategory(UUID id,Map<String,Object> body){
        jdbc.sql("""
                update categories set name=coalesce(:name,name),slug=coalesce(:slug,slug),description=coalesce(:description,description),
                status=coalesce(:status,status),parent_id=coalesce(:parent,parent_id),sort_order=coalesce(:sort,sort_order),updated_at=current_timestamp where id=:id
                """)
                .param("name",body.get("name")).param("slug",body.get("slug")).param("description",body.get("description"))
                .param("status",body.get("status")).param("parent",uuidValue(body.get("parentId"))).param("sort",body.get("sortOrder")).param("id",id).update();
        return category(String.valueOf(id)).orElseThrow();
    }
    @Transactional public void deleteCategory(UUID id){jdbc.sql("delete from categories where id=:id").param("id",id).update();}
    @Transactional public Map<String,Object> createBrand(Map<String,Object> body){
        UUID id=UUID.randomUUID();String slug=String.valueOf(body.get("slug"));jdbc.sql("insert into brands(id,code,name,slug,description,status) values(:id,:code,:name,:slug,:description,:status)")
                .param("id",id).param("code",code(slug)).param("name",body.get("name")).param("slug",slug)
                .param("description",body.get("description")).param("status",body.getOrDefault("status","ACTIVE")).update();return brand(id);
    }
    @Transactional public Map<String,Object> updateBrand(UUID id,Map<String,Object> body){jdbc.sql("update brands set name=coalesce(:name,name),slug=coalesce(:slug,slug),description=coalesce(:description,description),status=coalesce(:status,status),updated_at=current_timestamp where id=:id")
                .param("name",body.get("name")).param("slug",body.get("slug")).param("description",body.get("description")).param("status",body.get("status")).param("id",id).update();return brand(id);}
    @Transactional public void deleteBrand(UUID id){jdbc.sql("delete from brands where id=:id").param("id",id).update();}
    private Map<String,Object> brand(UUID id){return jdbc.sql("select id,name,slug,description,status from brands where id=:id").param("id",id).query((rs,row)->map("id",uuid(rs,"id"),"name",rs.getString("name"),"slug",rs.getString("slug"),"description",rs.getString("description"),"status",rs.getString("status"))).single();}

    @Transactional public Map<String,Object> createProduct(Map<String,Object> body){
        UUID id=UUID.randomUUID();Map<String,Object> attrs=productAttributes(body);jdbc.sql("""
                insert into products(id,category_id,brand_id,name,slug,description,attributes,status)
                values(:id,:category,:brand,:name,:slug,:description,cast(:attributes as jsonb),:status)
                """).param("id",id).param("category",uuidValue(body.get("categoryId"))).param("brand",uuidValue(body.get("brandId")))
                .param("name",body.get("name")).param("slug",body.get("slug")).param("description",body.get("description"))
                .param("attributes",jsonString(attrs)).param("status",body.getOrDefault("status","DRAFT")).update();return adminProduct(String.valueOf(id)).orElseThrow();
    }
    @Transactional public Map<String,Object> updateProduct(UUID id,Map<String,Object> body){
        Map<String,Object> attrs=productAttributes(body);jdbc.sql("""
                update products set name=coalesce(:name,name),slug=coalesce(:slug,slug),
                description=coalesce(:description,description),category_id=coalesce(:category,category_id),brand_id=coalesce(:brand,brand_id),
                status=coalesce(:status,status),attributes=attributes||cast(:attributes as jsonb),version=version+1,updated_at=current_timestamp where id=:id
                """)
                .param("name",body.get("name")).param("slug",body.get("slug")).param("description",body.get("description"))
                .param("category",uuidValue(body.get("categoryId"))).param("brand",uuidValue(body.get("brandId"))).param("status",body.get("status"))
                .param("attributes",jsonString(attrs)).param("id",id).update();return adminProduct(String.valueOf(id)).orElseThrow();
    }
    @Transactional public void deleteProduct(UUID id){jdbc.sql("update products set status='INACTIVE',version=version+1,updated_at=current_timestamp where id=:id").param("id",id).update();}
    @Transactional public Map<String,Object> createVariant(UUID productId,Map<String,Object> body){UUID id=UUID.randomUUID();jdbc.sql("""
            insert into product_variants(id,product_id,sku,size_label,color_label,price,compare_at_price,stock_quantity,status)
            values(:id,:product,:sku,:size,:color,:price,:compare,:stock,:status)""").param("id",id).param("product",productId)
            .param("sku",body.get("sku")).param("size",body.get("size")).param("color",body.get("color")).param("price",body.get("price"))
            .param("compare",body.get("compareAtPrice")).param("stock",number(body.get("stockQuantity"),0)).param("status",body.getOrDefault("status","ACTIVE")).update();return variant(id);}
    @Transactional public Map<String,Object> updateVariant(UUID id,Map<String,Object> body){jdbc.sql("""
            update product_variants set size_label=coalesce(:size,size_label),color_label=coalesce(:color,color_label),price=coalesce(:price,price),compare_at_price=coalesce(:compare,compare_at_price),status=coalesce(:status,status),version=version+1,updated_at=current_timestamp where id=:id
            """)
            .param("size",body.get("size")).param("color",body.get("color")).param("price",body.get("price")).param("compare",body.get("compareAtPrice")).param("status",body.get("status")).param("id",id).update();return variant(id);}
    private Map<String,Object> variant(UUID id){return jdbc.sql("select id,sku,size_label,color_label,price,compare_at_price,stock_quantity-reserved_quantity available_quantity,status from product_variants where id=:id").param("id",id).query((rs,row)->map("id",uuid(rs,"id"),"sku",rs.getString("sku"),"size",rs.getString("size_label"),"color",rs.getString("color_label"),"price",rs.getBigDecimal("price"),"compareAtPrice",rs.getBigDecimal("compare_at_price"),"availableQuantity",rs.getInt("available_quantity"),"status",rs.getString("status"))).single();}
    @Transactional public Map<String,Object> addImage(UUID productId,Map<String,Object> body){UUID id=UUID.randomUUID();boolean primary=Boolean.TRUE.equals(body.get("isPrimary"));if(primary)jdbc.sql("update product_images set is_primary=false where product_id=:product").param("product",productId).update();jdbc.sql("insert into product_images(id,product_id,secure_url,alt_text,sort_order,is_primary) values(:id,:product,:url,:alt,:sort,:primary)")
            .param("id",id).param("product",productId).param("url",body.get("imageUrl")).param("alt",body.get("altText")).param("sort",number(body.get("sortOrder"),0)).param("primary",primary).update();return image(id);}
    @Transactional public List<Map<String,Object>> deleteImage(UUID productId,UUID imageId){jdbc.sql("delete from product_images where id=:id and product_id=:product").param("id",imageId).param("product",productId).update();return images(productId);}
    @Transactional public List<Map<String,Object>> primaryImage(UUID productId,UUID imageId){jdbc.sql("update product_images set is_primary=(id=:id) where product_id=:product").param("id",imageId).param("product",productId).update();return images(productId);}
    private Map<String,Object> image(UUID id){return jdbc.sql("select id,secure_url,alt_text,is_primary,sort_order from product_images where id=:id").param("id",id).query((rs,row)->map("id",uuid(rs,"id"),"imageUrl",rs.getString("secure_url"),"publicId",null,"altText",rs.getString("alt_text"),"color",null,"isPrimary",rs.getBoolean("is_primary"),"sortOrder",rs.getInt("sort_order"))).single();}

    @Transactional
    public Map<String,Object> wishlist(UUID userId){
        UUID id=jdbc.sql("select id from wishlists where user_ref=:user").param("user",userId).query(UUID.class).optional()
                .orElseGet(()->{UUID created=UUID.randomUUID();jdbc.sql("insert into wishlists(id,user_ref) values(:id,:user)")
                        .param("id",created).param("user",userId).update();return created;});
        List<Map<String,Object>> items=jdbc.sql("""
                select wi.product_id,p.name,
                       (select i.secure_url from product_images i where i.product_id=p.id order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail,
                       wi.created_at from wishlist_items wi join products p on p.id=wi.product_id
                 where wi.wishlist_id=:id order by wi.created_at desc
                """).param("id",id).query((rs,row)->map("id",uuid(rs,"product_id"),"productId",uuid(rs,"product_id"),
                        "productName",rs.getString("name"),"thumbnail",rs.getString("thumbnail"),
                        "createdAt",rs.getTimestamp("created_at").toInstant())).list();
        return map("id",id,"items",items);
    }

    @Transactional
    public Map<String,Object> addWishlist(UUID userId,UUID productId){
        if(jdbc.sql("select count(*) from products where id=:id and status='ACTIVE'").param("id",productId).query(Long.class).single()==0)
            throw new IllegalArgumentException("Product not found");
        UUID wishlist=(UUID)wishlist(userId).get("id");
        jdbc.sql("insert into wishlist_items(wishlist_id,product_id) values(:wishlist,:product) on conflict do nothing")
                .param("wishlist",wishlist).param("product",productId).update();return wishlist(userId);
    }

    @Transactional
    public Map<String,Object> removeWishlist(UUID userId,UUID productId){
        jdbc.sql("delete from wishlist_items where wishlist_id=(select id from wishlists where user_ref=:user) and product_id=:product")
                .param("user",userId).param("product",productId).update();return wishlist(userId);
    }

    public long adminProductCount(){return jdbc.sql("select count(*) from products").query(Long.class).single();}
    public List<Map<String,Object>> adminProducts(int limit,int offset){
        return jdbc.sql("""
                select p.id,p.name,p.slug,p.status,p.attributes,b.id brand_id,b.name brand_name,c.id category_id,c.name category_name,
                       (select i.secure_url from product_images i where i.product_id=p.id order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail,
                       (select min(v.price) from product_variants v where v.product_id=p.id) min_price,
                       (select max(v.price) from product_variants v where v.product_id=p.id) max_price,
                       (select v.sku from product_variants v where v.product_id=p.id order by v.id limit 1) representative_sku,
                       (select coalesce(sum(v.stock_quantity-v.reserved_quantity),0) from product_variants v where v.product_id=p.id) available_quantity
                  from products p left join brands b on b.id=p.brand_id left join categories c on c.id=p.category_id
                 order by p.updated_at desc,p.id limit :limit offset :offset
                """).param("limit",limit).param("offset",offset).query((rs,row)->map(
                "id",uuid(rs,"id"),"name",rs.getString("name"),"slug",rs.getString("slug"),"status",rs.getString("status"),
                "isFeatured",booleanAttribute(rs,"isFeatured"),"brand",reference(rs,"brand_id","brand_name"),
                "category",reference(rs,"category_id","category_name"),"thumbnail",rs.getString("thumbnail"),
                "minPrice",rs.getBigDecimal("min_price"),"maxPrice",rs.getBigDecimal("max_price"),
                "representativeSku",rs.getString("representative_sku"),"availableQuantity",rs.getInt("available_quantity"))).list();
    }

    public long inventoryCount(String keyword){String q=clean(keyword);return jdbc.sql("""
            select count(*) from product_variants v join products p on p.id=v.product_id
             where (:q='' or lower(v.sku) like lower(:like) or lower(p.name) like lower(:like))
            """).param("q",q).param("like","%"+q+"%").query(Long.class).single();}
    public List<Map<String,Object>> inventory(String keyword,int limit,int offset){String q=clean(keyword);return jdbc.sql("""
            select v.id variant_id,p.id product_id,p.name product_name,v.sku,v.size_label,v.color_label,
                   v.stock_quantity,v.reserved_quantity,v.stock_quantity-v.reserved_quantity available_quantity,
                   (select i.secure_url from product_images i where i.product_id=p.id order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail
              from product_variants v join products p on p.id=v.product_id
             where (:q='' or lower(v.sku) like lower(:like) or lower(p.name) like lower(:like))
             order by p.name,v.sku limit :limit offset :offset
            """).param("q",q).param("like","%"+q+"%").param("limit",limit).param("offset",offset).query((rs,row)->map(
            "variantId",uuid(rs,"variant_id"),"productId",uuid(rs,"product_id"),"productName",rs.getString("product_name"),
            "thumbnail",rs.getString("thumbnail"),"sku",rs.getString("sku"),"size",rs.getString("size_label"),
            "color",rs.getString("color_label"),"stockQuantity",rs.getInt("stock_quantity"),
            "reservedQuantity",rs.getInt("reserved_quantity"),"availableQuantity",rs.getInt("available_quantity"))).list();}

    public long inventoryTransactionCount(){return jdbc.sql("select count(*) from inventory_transactions").query(Long.class).single();}
    public List<Map<String,Object>> inventoryTransactions(int limit,int offset){return jdbc.sql("""
            select t.*,v.sku from inventory_transactions t join product_variants v on v.id=t.variant_id
             order by t.created_at desc,t.id limit :limit offset :offset
            """).param("limit",limit).param("offset",offset).query((rs,row)->map(
            "id",uuid(rs,"id"),"variantId",uuid(rs,"variant_id"),"sku",rs.getString("sku"),"orderId",uuid(rs,"order_ref"),
            "type",rs.getString("transaction_type"),"quantity",rs.getInt("quantity"),
            "beforeStockQuantity",rs.getInt("before_stock_quantity"),"afterStockQuantity",rs.getInt("after_stock_quantity"),
            "beforeReservedQuantity",rs.getInt("before_reserved_quantity"),"afterReservedQuantity",rs.getInt("after_reserved_quantity"),
            "note",rs.getString("reason"),"createdById",uuid(rs,"actor_ref"),"createdByName",null,
            "createdAt",rs.getTimestamp("created_at").toInstant())).list();}

    @Transactional public Map<String,Object> changeStock(UUID requestId,UUID variantId,int quantity,String type,String note,UUID actor){
        Map<String,Object> current=jdbc.sql("select stock_quantity,reserved_quantity from product_variants where id=:id for update")
                .param("id",variantId).query().singleRow();int before=((Number)current.get("stock_quantity")).intValue();
        int reserved=((Number)current.get("reserved_quantity")).intValue();int delta=switch(type){case "IMPORT","ADJUSTMENT_UP"->quantity;case "EXPORT","ADJUSTMENT_DOWN"->-quantity;default->throw new IllegalArgumentException("Unsupported inventory type");};
        int after=before+delta;if(after<reserved)throw new IllegalStateException("Stock cannot be lower than reserved quantity");
        String key="admin-stock:"+(requestId==null?UUID.randomUUID():requestId);int inserted=jdbc.sql("""
                insert into inventory_transactions(id,variant_id,transaction_type,quantity,idempotency_key,before_stock_quantity,after_stock_quantity,before_reserved_quantity,after_reserved_quantity,actor_ref,reason)
                values(:id,:variant,:type,:quantity,:key,:before,:after,:reserved,:reserved,:actor,:reason) on conflict(idempotency_key) do nothing
                """).param("id",UUID.randomUUID()).param("variant",variantId).param("type",type).param("quantity",Math.abs(quantity))
                .param("key",key).param("before",before).param("after",after).param("reserved",reserved).param("actor",actor).param("reason",note).update();
        if(inserted>0)jdbc.sql("update product_variants set stock_quantity=:stock,version=version+1,updated_at=current_timestamp where id=:id").param("stock",after).param("id",variantId).update();
        return inventoryItem(variantId);
    }
    private Map<String,Object> inventoryItem(UUID variantId){return jdbc.sql("""
            select v.id variant_id,p.id product_id,p.name product_name,v.sku,v.size_label,v.color_label,v.stock_quantity,v.reserved_quantity,
                   v.stock_quantity-v.reserved_quantity available_quantity,(select i.secure_url from product_images i where i.product_id=p.id order by i.is_primary desc,i.sort_order,i.id limit 1) thumbnail
              from product_variants v join products p on p.id=v.product_id where v.id=:id
            """).param("id",variantId).query((rs,row)->map("variantId",uuid(rs,"variant_id"),"productId",uuid(rs,"product_id"),"productName",rs.getString("product_name"),"thumbnail",rs.getString("thumbnail"),"sku",rs.getString("sku"),"size",rs.getString("size_label"),"color",rs.getString("color_label"),"stockQuantity",rs.getInt("stock_quantity"),"reservedQuantity",rs.getInt("reserved_quantity"),"availableQuantity",rs.getInt("available_quantity"))).single();}

    private List<Map<String, Object>> images(UUID productId) {
        return jdbc.sql("""
                select id,secure_url,alt_text,is_primary,sort_order from product_images
                 where product_id=:id order by is_primary desc,sort_order,id
                """).param("id", productId).query((rs, row) -> map(
                "id", uuid(rs, "id"), "imageUrl", rs.getString("secure_url"), "publicId", null,
                "altText", rs.getString("alt_text"), "color", null,
                "isPrimary", rs.getBoolean("is_primary"), "sortOrder", rs.getInt("sort_order"))).list();
    }

    private List<Map<String, Object>> variants(UUID productId) {
        return jdbc.sql("""
                select id,sku,size_label,color_label,price,compare_at_price,
                       stock_quantity-reserved_quantity available_quantity,status
                  from product_variants where product_id=:id and status='ACTIVE' order by price,id
                """).param("id", productId).query((rs, row) -> map(
                "id", uuid(rs, "id"), "sku", rs.getString("sku"), "size", rs.getString("size_label"),
                "color", rs.getString("color_label"), "price", rs.getBigDecimal("price"),
                "compareAtPrice", rs.getBigDecimal("compare_at_price"),
                "availableQuantity", rs.getInt("available_quantity"), "status", rs.getString("status"))).list();
    }

    private Map<String, Object> reviewSummary(UUID productId) {
        return jdbc.sql("""
                select coalesce(avg(rating),0) average_rating,count(*) review_count
                  from product_reviews where product_id=:id and status='APPROVED'
                """).param("id", productId).query((rs, row) -> map(
                "averageRating", rs.getDouble("average_rating"), "reviewCount", rs.getLong("review_count"))).single();
    }

    private Map<String, Object> category(ResultSet rs) throws SQLException {
        return map("id", uuid(rs, "id"), "name", rs.getString("name"), "slug", rs.getString("slug"),
                "description", rs.getString("description"), "status", rs.getString("status"),
                "parentId", uuid(rs, "parent_id"), "parentName", rs.getString("parent_name"),
                "parentSlug", rs.getString("parent_slug"), "nodeType", "CATEGORY",
                "sortOrder", rs.getInt("sort_order"));
    }

    private Map<String, Object> productListItem(ResultSet rs) throws SQLException {
        BigDecimal sale = rs.getBigDecimal("sale_price");
        BigDecimal compare = rs.getBigDecimal("compare_at_price");
        Integer discount = null;
        if (sale != null && compare != null && compare.signum() > 0 && compare.compareTo(sale) > 0) {
            discount = compare.subtract(sale).multiply(BigDecimal.valueOf(100)).divide(compare, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
        return map("id", uuid(rs, "id"), "name", rs.getString("name"), "slug", rs.getString("slug"),
                "shortDescription", shortDescription(rs.getString("description")),
                "brand", reference(rs, "brand_id", "brand_name"),
                "category", reference(rs, "category_id", "category_name"), "thumbnail", rs.getString("thumbnail"),
                "minPrice", rs.getBigDecimal("min_price"), "maxPrice", rs.getBigDecimal("max_price"),
                "salePrice", sale, "compareAtPrice", compare, "discountPercent", discount,
                "availableQuantity", rs.getInt("available_quantity"), "status", rs.getString("status"),
                "productType", attribute(rs, "productType"));
    }

    private Map<String, Object> reference(ResultSet rs, String idColumn, String nameColumn) throws SQLException {
        UUID id = uuid(rs, idColumn);
        return id == null ? null : map("id", id, "name", rs.getString(nameColumn));
    }

    private Map<String, Object> attributes(ResultSet rs) throws SQLException {
        String raw = rs.getString("attributes");
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try { return new LinkedHashMap<>(json.readValue(raw, JSON_MAP)); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private Object attribute(ResultSet rs, String key) throws SQLException { return attributes(rs).get(key); }
    private boolean booleanAttribute(ResultSet rs, String key) throws SQLException {
        Object value = attributes(rs).get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String shortDescription(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> productAttributes(Map<String,Object> body){
        Map<String,Object> result=new LinkedHashMap<>();Object supplied=body.get("attributes");if(supplied instanceof Map<?,?> map)map.forEach((k,v)->result.put(String.valueOf(k),v));
        for(String key:List.of("shortDescription","gender","sportType","productType","isFeatured"))if(body.containsKey(key))result.put(key,body.get(key));return result;
    }
    private String jsonString(Map<String,Object> value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalArgumentException("Invalid attributes",error);}}
    private static UUID uuidValue(Object value){return value==null||String.valueOf(value).isBlank()?null:UUID.fromString(String.valueOf(value));}
    private static int number(Object value,int fallback){return value instanceof Number number?number.intValue():value==null?fallback:Integer.parseInt(String.valueOf(value));}
    private static String code(String slug){return slug.replace('-','_').toUpperCase(java.util.Locale.ROOT);}
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
