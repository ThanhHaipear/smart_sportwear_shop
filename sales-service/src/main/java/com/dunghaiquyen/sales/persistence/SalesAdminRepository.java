package com.dunghaiquyen.sales.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SalesAdminRepository {
    private final JdbcClient jdbc; private final SalesQueryRepository queries;
    public SalesAdminRepository(JdbcClient jdbc,SalesQueryRepository queries){this.jdbc=jdbc;this.queries=queries;}
    public long orderCount(){return jdbc.sql("select count(*) from orders").query(Long.class).single();}
    public List<Map<String,Object>> orders(int limit,int offset){return jdbc.sql("select * from orders order by placed_at desc,id limit :limit offset :offset")
            .param("limit",limit).param("offset",offset).query((rs,row)->adminOrder(rs)).list();}
    public Map<String,Object> order(UUID id){return queries.order(new UUID(0,0),id,true).orElseThrow(()->new IllegalArgumentException("Order not found"));}
    @Transactional public Map<String,Object> status(UUID id,String status,String reason,UUID actor){
        String before=jdbc.sql("select order_status from orders where id=:id for update").param("id",id).query(String.class).single();
        jdbc.sql("update orders set order_status=:status,updated_at=current_timestamp where id=:id").param("status",status).param("id",id).update();
        jdbc.sql("insert into order_status_history(order_id,from_status,to_status,reason,actor_ref) values(:id,:before,:after,:reason,:actor)")
                .param("id",id).param("before",before).param("after",status).param("reason",reason).param("actor",actor).update();return order(id);}
    public Map<String,Object> overview(){return jdbc.sql("""
            select count(*) orders,count(*) filter(where order_status not in ('DELIVERED','CANCELLED')) active_orders,
                   coalesce(sum(total_amount) filter(where payment_status='PAID'),0) revenue,
                   count(distinct user_ref) customers from orders
            """).query((rs,row)->map("orders",rs.getLong("orders"),"activeOrders",rs.getLong("active_orders"),
                    "revenue",rs.getBigDecimal("revenue"),"customers",rs.getLong("customers"))).single();}
    public Map<String,Object> orderReport(){return map("items",jdbc.sql("select order_status status,count(*) count from orders group by order_status order by order_status").query().listOfRows());}
    public Map<String,Object> revenueReport(){return map("items",jdbc.sql("select cast(placed_at as date) date,coalesce(sum(total_amount) filter(where payment_status='PAID'),0) revenue from orders group by cast(placed_at as date) order by date").query().listOfRows());}
    public Map<String,Object> productReport(int limit){return map("bestSelling",jdbc.sql("""
            select product_ref product_id,max(product_name_snapshot) product_name,sum(quantity) total_quantity_sold
              from order_items group by product_ref order by total_quantity_sold desc limit :limit
            """).param("limit",limit).query((rs,row)->map("productId",uuid(rs,"product_id"),"productName",rs.getString("product_name"),"totalQuantitySold",rs.getLong("total_quantity_sold"))).list());}
    public Map<String,Object> customerReport(){return map("items",jdbc.sql("select user_ref,count(*) order_count,sum(total_amount) total_spent from orders group by user_ref order by total_spent desc").query().listOfRows());}
    public Map<String,Object> inventoryReport(){return map("message","Inventory report is owned by catalog-service","items",List.of());}
    public long returnCount(){return jdbc.sql("select count(*) from returns").query(Long.class).single();}
    public List<Map<String,Object>> returns(int limit,int offset){return jdbc.sql("select * from returns order by requested_at desc,id limit :limit offset :offset").param("limit",limit).param("offset",offset).query().listOfRows();}
    public Map<String,Object> returnDetail(UUID id){Map<String,Object> result=new LinkedHashMap<>(jdbc.sql("select * from returns where id=:id").param("id",id).query().singleRow());result.put("items",jdbc.sql("select * from return_items where return_id=:id").param("id",id).query().listOfRows());return result;}
    @Transactional public Map<String,Object> returnStatus(UUID id,String status,UUID actor){jdbc.sql("update returns set status=:status,handled_by_ref=:actor,updated_at=current_timestamp where id=:id").param("status",status).param("actor",actor).param("id",id).update();return returnDetail(id);}
    public List<Map<String,Object>> refunds(UUID orderId){return jdbc.sql("select * from refunds where order_id=:id order by created_at desc").param("id",orderId).query().listOfRows();}
    public List<Map<String,Object>> settings(){return jdbc.sql("select setting_key key,setting_value value,value_type type,description,is_public from site_settings order by setting_key").query().listOfRows();}
    @Transactional public Map<String,Object> setting(String key,Object value,String type,String description,boolean isPublic,UUID actor){
        jdbc.sql("""
                insert into site_settings(id,setting_key,setting_value,value_type,description,is_public,updated_by_ref)
                values(:id,:key,:value,:type,:description,:public,:actor) on conflict(setting_key) do update set setting_value=excluded.setting_value,
                value_type=excluded.value_type,description=excluded.description,is_public=excluded.is_public,updated_by_ref=excluded.updated_by_ref,updated_at=current_timestamp
                """)
                .param("id",UUID.randomUUID()).param("key",key).param("value",String.valueOf(value)).param("type",type==null?"STRING":type)
                .param("description",description).param("public",isPublic).param("actor",actor).update();return jdbc.sql("select setting_key key,setting_value value,value_type type,description,is_public from site_settings where setting_key=:key").param("key",key).query().singleRow();}
    public long auditCount(){return jdbc.sql("select count(*) from audit_logs").query(Long.class).single();}
    public List<Map<String,Object>> audits(int limit,int offset){return jdbc.sql("select * from audit_logs order by created_at desc,id limit :limit offset :offset").param("limit",limit).param("offset",offset).query().listOfRows();}
    private Map<String,Object> adminOrder(ResultSet rs)throws SQLException{return map("id",uuid(rs,"id"),"orderCode",rs.getString("order_code"),"userId",uuid(rs,"user_ref"),"orderStatus",rs.getString("order_status"),"paymentStatus",rs.getString("payment_status"),"paymentMethod",rs.getString("payment_method"),"totalAmount",rs.getBigDecimal("total_amount"),"createdAt",rs.getTimestamp("created_at").toInstant());}
    private static UUID uuid(ResultSet rs,String column)throws SQLException{return rs.getObject(column,UUID.class);}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
}
