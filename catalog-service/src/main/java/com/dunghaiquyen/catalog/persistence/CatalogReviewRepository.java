package com.dunghaiquyen.catalog.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogReviewRepository {
    private final JdbcClient jdbc;
    public CatalogReviewRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public UUID productId(String idOrSlug){
        try{return jdbc.sql("select id from products where id=:id").param("id",UUID.fromString(idOrSlug)).query(UUID.class).optional().orElseThrow();}
        catch(Exception ignored){return jdbc.sql("select id from products where slug=:slug").param("slug",idOrSlug).query(UUID.class).optional().orElseThrow(()->new IllegalArgumentException("Product not found"));}
    }
    public long count(UUID product,String status){return jdbc.sql("select count(*) from product_reviews where (:product is null or product_id=:product) and (:status is null or status=:status)").param("product",product).param("status",status).query(Long.class).single();}
    public List<Map<String,Object>> list(UUID product,String status,int limit,int offset){return jdbc.sql("select * from product_reviews where (:product is null or product_id=:product) and (:status is null or status=:status) order by created_at desc,id limit :limit offset :offset").param("product",product).param("status",status).param("limit",limit).param("offset",offset).query((rs,n)->map("id",rs.getObject("id",UUID.class),"productId",rs.getObject("product_id",UUID.class),"userId",rs.getObject("user_ref",UUID.class),"reviewerName","Verified customer","rating",rs.getInt("rating"),"title",rs.getString("title"),"content",rs.getString("content"),"status",rs.getString("status"),"verifiedPurchase",rs.getObject("order_item_ref")!=null,"createdAt",rs.getTimestamp("created_at").toInstant())).list();}
    @Transactional public Map<String,Object> create(UUID product,UUID user,UUID orderItem,int rating,String title,String content){
        if(rating<1||rating>5)throw new IllegalArgumentException("Rating must be between 1 and 5");
        UUID id=UUID.randomUUID();jdbc.sql("insert into product_reviews(id,product_id,user_ref,order_item_ref,rating,title,content,status) values(:id,:product,:user,:item,:rating,:title,:content,'PENDING')")
                .param("id",id).param("product",product).param("user",user).param("item",orderItem).param("rating",rating).param("title",title).param("content",content).update();return detail(id);}
    public Map<String,Object> detail(UUID id){return jdbc.sql("select * from product_reviews where id=:id").param("id",id).query((rs,n)->map("id",rs.getObject("id",UUID.class),"productId",rs.getObject("product_id",UUID.class),"userId",rs.getObject("user_ref",UUID.class),"reviewerName","Verified customer","rating",rs.getInt("rating"),"title",rs.getString("title"),"content",rs.getString("content"),"status",rs.getString("status"),"verifiedPurchase",rs.getObject("order_item_ref")!=null,"createdAt",rs.getTimestamp("created_at").toInstant())).optional().orElseThrow(()->new IllegalArgumentException("Review not found"));}
    @Transactional public Map<String,Object> status(UUID id,String status){if(!List.of("PENDING","APPROVED","REJECTED").contains(status))throw new IllegalArgumentException("Invalid review status");jdbc.sql("update product_reviews set status=:status,updated_at=current_timestamp where id=:id").param("status",status).param("id",id).update();return detail(id);}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
