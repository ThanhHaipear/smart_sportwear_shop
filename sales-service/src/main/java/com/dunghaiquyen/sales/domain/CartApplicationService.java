package com.dunghaiquyen.sales.domain;

import com.dunghaiquyen.sales.catalog.CatalogClient;
import com.dunghaiquyen.sales.domain.SalesModels.VariantSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartApplicationService {
    private final JdbcClient jdbc;
    private final CatalogClient catalog;
    public CartApplicationService(JdbcClient jdbc,CatalogClient catalog){this.jdbc=jdbc;this.catalog=catalog;}

    @Transactional
    public Map<String,Object> getOrCreate(UUID userId,String sessionId){return response(cartId(userId,sessionId,true));}

    @Transactional
    public Map<String,Object> add(UUID userId,String sessionId,UUID variantId,int quantity){
        UUID cart=cartId(userId,sessionId,true);VariantSnapshot snapshot=catalog.snapshot(variantId);
        if(!snapshot.active())throw new IllegalArgumentException("Variant is not active");
        Integer existing=jdbc.sql("select quantity from cart_items where cart_id=:cart and variant_ref=:variant")
                .param("cart",cart).param("variant",variantId).query(Integer.class).optional().orElse(0);
        if(existing+quantity>snapshot.availableQuantity())throw new IllegalStateException("Insufficient inventory");
        jdbc.sql("""
                insert into cart_items(id,cart_id,variant_ref,quantity) values(:id,:cart,:variant,:quantity)
                on conflict(cart_id,variant_ref) do update set quantity=cart_items.quantity+excluded.quantity,updated_at=current_timestamp
                """).param("id",UUID.randomUUID()).param("cart",cart).param("variant",variantId).param("quantity",quantity).update();
        touch(cart);return response(cart);
    }

    @Transactional
    public Map<String,Object> update(UUID userId,String sessionId,UUID itemId,int quantity){
        UUID cart=cartId(userId,sessionId,false);UUID variant=jdbc.sql("select variant_ref from cart_items where id=:id and cart_id=:cart")
                .param("id",itemId).param("cart",cart).query(UUID.class).optional()
                .orElseThrow(()->new IllegalArgumentException("Cart item not found"));
        VariantSnapshot snapshot=catalog.snapshot(variant);if(quantity>snapshot.availableQuantity())throw new IllegalStateException("Insufficient inventory");
        jdbc.sql("update cart_items set quantity=:quantity,updated_at=current_timestamp where id=:id")
                .param("quantity",quantity).param("id",itemId).update();touch(cart);return response(cart);
    }

    @Transactional
    public Map<String,Object> remove(UUID userId,String sessionId,UUID itemId){
        UUID cart=cartId(userId,sessionId,false);jdbc.sql("delete from cart_items where id=:id and cart_id=:cart")
                .param("id",itemId).param("cart",cart).update();touch(cart);return response(cart);
    }

    public List<SalesModels.OrderLineRequest> orderLines(UUID userId,List<UUID> selected){
        String sql=selected==null||selected.isEmpty()
                ?"select variant_ref,quantity from cart_items where cart_id=(select id from carts where user_ref=:user) order by id"
                :"select variant_ref,quantity from cart_items where cart_id=(select id from carts where user_ref=:user) and id in (:ids) order by id";
        var statement=jdbc.sql(sql).param("user",userId);if(selected!=null&&!selected.isEmpty())statement=statement.param("ids",selected);
        return statement.query((rs,row)->new SalesModels.OrderLineRequest(rs.getObject("variant_ref",UUID.class),rs.getInt("quantity"))).list();
    }

    private UUID cartId(UUID userId,String sessionId,boolean create){
        var result=userId!=null?jdbc.sql("select id from carts where user_ref=:owner").param("owner",userId).query(UUID.class).optional()
                :jdbc.sql("select id from carts where session_id=:owner").param("owner",sessionId).query(UUID.class).optional();
        if(result.isPresent())return result.get();if(!create)throw new IllegalArgumentException("Cart not found");
        UUID id=UUID.randomUUID();jdbc.sql("insert into carts(id,user_ref,session_id) values(:id,:user,:session)")
                .param("id",id).param("user",userId).param("session",userId==null?sessionId:null).update();return id;
    }

    private Map<String,Object> response(UUID cartId){
        List<Map<String,Object>> items=new ArrayList<>();BigDecimal subtotal=BigDecimal.ZERO;
        for(Map<String,Object> row:jdbc.sql("select id,variant_ref,quantity from cart_items where cart_id=:cart order by created_at,id")
                .param("cart",cartId).query().listOfRows()){
            UUID variantId=(UUID)row.get("variant_ref");int quantity=((Number)row.get("quantity")).intValue();VariantSnapshot s=catalog.snapshot(variantId);
            BigDecimal line=s.price().multiply(BigDecimal.valueOf(quantity));subtotal=subtotal.add(line);
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",row.get("id"));item.put("variantId",variantId);item.put("productId",s.productId());
            item.put("productName",s.productName());item.put("sku",s.sku());item.put("size",s.size());item.put("color",s.color());
            item.put("price",s.price());item.put("quantity",quantity);item.put("lineTotal",line);item.put("thumbnail",null);items.add(item);
        }
        Map<String,Object> response=new LinkedHashMap<>();response.put("id",cartId);response.put("items",items);response.put("subtotal",subtotal);return response;
    }
    private void touch(UUID cart){jdbc.sql("update carts set updated_at=current_timestamp where id=:id").param("id",cart).update();}
}
