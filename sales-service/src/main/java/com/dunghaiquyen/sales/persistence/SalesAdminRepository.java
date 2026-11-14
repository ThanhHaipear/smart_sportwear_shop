package com.dunghaiquyen.sales.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
            select count(*) orders,
                   count(*) filter(where order_status not in ('DELIVERED','CANCELLED')) active_orders,
                   coalesce((select sum(p.amount) from payments p join orders paid_order on paid_order.id=p.order_id
                              where p.paid_at is not null and p.status in ('PAID','PARTIALLY_REFUNDED','REFUNDED')
                                and paid_order.data_source='REAL'),0) gross_revenue,
                   coalesce((select sum(r.amount) from refunds r join orders refunded_order on refunded_order.id=r.order_id
                              where r.status='COMPLETED' and r.refunded_at is not null
                                and refunded_order.data_source='REAL'),0) completed_refunds,
                   count(distinct user_ref) customers
              from orders where data_source='REAL'
            """).query((rs,row)->{
                BigDecimal gross=rs.getBigDecimal("gross_revenue");
                BigDecimal realized=gross.subtract(rs.getBigDecimal("completed_refunds"));
                long orders=rs.getLong("orders"),activeOrders=rs.getLong("active_orders");
                return map("orders",orders,"activeOrders",activeOrders,"revenue",gross,"customers",rs.getLong("customers"),
                        "grossRevenue",gross,"realizedRevenue",realized,"totalOrders",orders,"pendingOrders",activeOrders,"lowStockCount",0);
            }).single();}
    public Map<String,Object> orderReport(){return map("items",jdbc.sql("select order_status status,count(*) count from orders group by order_status order by order_status").query().listOfRows());}
    public Map<String,Object> revenueReport(String granularity,LocalDate dateFrom,LocalDate dateTo){
        if(dateFrom!=null&&dateTo!=null&&dateFrom.isAfter(dateTo))throw new IllegalArgumentException("dateFrom must not be after dateTo");
        String unit=switch(granularity){case "DAY"->"day";case "MONTH"->"month";case "YEAR"->"year";default->throw new IllegalArgumentException("Unsupported revenue granularity: "+granularity);};
        String labelFormat=switch(granularity){case "DAY"->"DD/MM/YYYY";case "MONTH"->"MM/YYYY";default->"YYYY";};
        StringBuilder sql=new StringBuilder("""
                select to_char(date_trunc('%s',p.paid_at at time zone 'Asia/Ho_Chi_Minh'),'YYYY-MM-DD') date,
                       to_char(date_trunc('%s',p.paid_at at time zone 'Asia/Ho_Chi_Minh'),'%s') label,
                       coalesce(sum(p.amount),0) revenue,count(distinct p.order_id) orders,
                       to_char(min((p.paid_at at time zone 'Asia/Ho_Chi_Minh')::date),'YYYY-MM-DD') period_from,
                       to_char(max((p.paid_at at time zone 'Asia/Ho_Chi_Minh')::date),'YYYY-MM-DD') period_to
                  from payments p join orders o on o.id=p.order_id
                 where p.paid_at is not null and p.status in ('PAID','PARTIALLY_REFUNDED','REFUNDED')
                   and o.data_source='REAL'
                """.formatted(unit,unit,labelFormat));
        if(dateFrom!=null)sql.append(" and (p.paid_at at time zone 'Asia/Ho_Chi_Minh')::date>=:dateFrom");
        if(dateTo!=null)sql.append(" and (p.paid_at at time zone 'Asia/Ho_Chi_Minh')::date<=:dateTo");
        sql.append(" group by date_trunc('").append(unit).append("',p.paid_at at time zone 'Asia/Ho_Chi_Minh') order by 1");
        JdbcClient.StatementSpec statement=jdbc.sql(sql.toString());
        if(dateFrom!=null)statement=statement.param("dateFrom",dateFrom);
        if(dateTo!=null)statement=statement.param("dateTo",dateTo);
        List<Map<String,Object>> points=statement.query().listOfRows();
        String first=dateFrom==null?(points.isEmpty()?"":String.valueOf(points.getFirst().get("period_from"))):dateFrom.toString();
        String last=dateTo==null?(points.isEmpty()?"":String.valueOf(points.getLast().get("period_to"))):dateTo.toString();
        points.forEach(point->{point.remove("period_from");point.remove("period_to");});
        return map("granularity",granularity,"dateFrom",first,"dateTo",last,"points",points);
    }
    public Map<String,Object> productReport(int limit){return map("bestSelling",jdbc.sql("""
            select i.product_ref product_id,max(i.product_name_snapshot) product_name,sum(i.quantity) total_quantity_sold,
                   sum(i.net_line_amount) total_revenue
              from order_items i join orders o on o.id=i.order_id
             where o.order_status='DELIVERED' and o.data_source='REAL'
             group by i.product_ref order by total_quantity_sold desc limit :limit
            """).param("limit",limit).query((rs,row)->map("productId",uuid(rs,"product_id"),"productName",rs.getString("product_name"),"totalQuantitySold",rs.getLong("total_quantity_sold"),"totalRevenue",rs.getBigDecimal("total_revenue"))).list());}
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
