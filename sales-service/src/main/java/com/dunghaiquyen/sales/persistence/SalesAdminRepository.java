package com.dunghaiquyen.sales.persistence;

import com.dunghaiquyen.sales.identity.IdentityClient;
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
    private final JdbcClient jdbc; private final SalesQueryRepository queries; private final SalesWorkflowRepository workflows; private final IdentityClient identity;
    public SalesAdminRepository(JdbcClient jdbc,SalesQueryRepository queries,SalesWorkflowRepository workflows,IdentityClient identity){this.jdbc=jdbc;this.queries=queries;this.workflows=workflows;this.identity=identity;}
    public long orderCount(String keyword,String status,UUID customerId,LocalDate dateFrom,LocalDate dateTo,UUID productId){
        return bindOrderFilters(jdbc.sql("select count(*) from orders o where "+orderFilters(keyword,status,customerId,dateFrom,dateTo,productId)),keyword,status,customerId,dateFrom,dateTo,productId).query(Long.class).single();
    }
    public List<Map<String,Object>> orders(int limit,int offset,String keyword,String status,UUID customerId,LocalDate dateFrom,LocalDate dateTo,UUID productId){
        JdbcClient.StatementSpec statement=bindOrderFilters(jdbc.sql("select o.id from orders o where "+orderFilters(keyword,status,customerId,dateFrom,dateTo,productId)+" order by o.placed_at desc,o.id limit :limit offset :offset"),keyword,status,customerId,dateFrom,dateTo,productId)
                .param("limit",limit).param("offset",offset);
        return statement.query(UUID.class).list().stream().map(this::order).toList();
    }
    public Map<String,Object> order(UUID id){
        Map<String,Object> result=new LinkedHashMap<>(queries.order(new UUID(0,0),id,true).orElseThrow(()->new IllegalArgumentException("Order not found")));
        UUID customerId=(UUID)result.get("customerId"); Map<String,Object> profile=user(customerId);
        @SuppressWarnings("unchecked") Map<String,Object> address=result.get("shippingAddress") instanceof Map<?,?> value?(Map<String,Object>)value:Map.of();
        result.put("customerName",first(profile.get("fullName"),address.get("receiverName"),customerId));
        result.put("customerPhone",first(profile.get("phone"),address.get("phone"),null));
        return result;
    }
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
    public Map<String,Object> productReport(int page,int limit,LocalDate dateFrom,LocalDate dateTo){
        String dateSql=reportDateFilters("o.delivered_at",dateFrom,dateTo);
        long total=bindDates(jdbc.sql("select count(distinct i.product_ref) from order_items i join orders o on o.id=i.order_id where o.order_status='DELIVERED' and o.data_source='REAL'"+dateSql),dateFrom,dateTo).query(Long.class).single();
        JdbcClient.StatementSpec statement=bindDates(jdbc.sql("""
            select i.product_ref product_id,max(i.product_name_snapshot) product_name,sum(i.quantity) total_quantity_sold,
                   sum(i.net_line_amount) total_revenue
              from order_items i join orders o on o.id=i.order_id
             where o.order_status='DELIVERED' and o.data_source='REAL'
            """+dateSql+" group by i.product_ref order by total_quantity_sold desc limit :limit offset :offset"),dateFrom,dateTo)
                .param("limit",limit).param("offset",(page-1)*limit);
        List<Map<String,Object>> items=statement.query((rs,row)->map("productId",uuid(rs,"product_id"),"productName",rs.getString("product_name"),"totalQuantitySold",rs.getLong("total_quantity_sold"),"totalRevenue",rs.getBigDecimal("total_revenue"))).list();
        return map("dateFrom",dateFrom==null?null:dateFrom.toString(),"dateTo",dateTo==null?null:dateTo.toString(),"bestSelling",items,"meta",meta(page,limit,total));
    }
    public Map<String,Object> customerReport(int page,int limit,LocalDate dateFrom,LocalDate dateTo){
        String dateSql=reportDateFilters("o.placed_at",dateFrom,dateTo);
        String base=" from orders o where o.order_status<>'CANCELLED' and o.data_source='REAL'"+dateSql;
        long total=bindDates(jdbc.sql("select count(distinct o.user_ref)"+base),dateFrom,dateTo).query(Long.class).single();
        JdbcClient.StatementSpec statement=bindDates(jdbc.sql("select o.user_ref customer_id,count(*) total_orders,sum(o.total_amount) total_revenue"+base+" group by o.user_ref order by total_revenue desc limit :limit offset :offset"),dateFrom,dateTo)
                .param("limit",limit).param("offset",(page-1)*limit);
        List<Map<String,Object>> customers=statement.query((rs,row)->{
            UUID id=uuid(rs,"customer_id");Map<String,Object> profile=user(id);
            return map("customerId",id,"customerName",first(profile.get("fullName"),null,id),"email",first(profile.get("email"),null,""),"totalOrders",rs.getLong("total_orders"),"totalRevenue",rs.getBigDecimal("total_revenue"));
        }).list();
        return map("dateFrom",dateFrom==null?null:dateFrom.toString(),"dateTo",dateTo==null?null:dateTo.toString(),"customers",customers,"meta",meta(page,limit,total));
    }
    public Map<String,Object> inventoryReport(){return map("message","Inventory report is owned by catalog-service","items",List.of());}
    public long returnCount(String status){JdbcClient.StatementSpec statement=jdbc.sql("select count(*) from returns where (:all=true or status=:status)").param("all",status==null).param("status",status==null?"":status);return statement.query(Long.class).single();}
    public List<Map<String,Object>> returns(int limit,int offset,String status){return jdbc.sql("select id from returns where (:all=true or status=:status) order by requested_at desc,id limit :limit offset :offset")
            .param("all",status==null).param("status",status==null?"":status).param("limit",limit).param("offset",offset).query(UUID.class).list().stream().map(id->workflows.returnDetail(id,null,true)).toList();}
    public Map<String,Object> returnDetail(UUID id){return workflows.returnDetail(id,null,true);}
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
    private String orderFilters(String keyword,String status,UUID customerId,LocalDate dateFrom,LocalDate dateTo,UUID productId){
        StringBuilder sql=new StringBuilder("o.data_source='REAL'");
        if(keyword!=null&&!keyword.isBlank())sql.append(" and lower(o.order_code) like lower(:keyword)");
        if(status!=null&&!status.isBlank())sql.append(" and o.order_status=:status");
        if(customerId!=null)sql.append(" and o.user_ref=:customerId");
        if(dateFrom!=null)sql.append(" and (o.placed_at at time zone 'Asia/Ho_Chi_Minh')::date>=:dateFrom");
        if(dateTo!=null)sql.append(" and (o.placed_at at time zone 'Asia/Ho_Chi_Minh')::date<=:dateTo");
        if(productId!=null)sql.append(" and exists(select 1 from order_items i where i.order_id=o.id and i.product_ref=:productId)");
        return sql.toString();
    }
    private JdbcClient.StatementSpec bindOrderFilters(JdbcClient.StatementSpec statement,String keyword,String status,UUID customerId,LocalDate dateFrom,LocalDate dateTo,UUID productId){
        if(keyword!=null&&!keyword.isBlank())statement=statement.param("keyword","%"+keyword.trim()+"%");
        if(status!=null&&!status.isBlank())statement=statement.param("status",status);
        if(customerId!=null)statement=statement.param("customerId",customerId);
        if(productId!=null)statement=statement.param("productId",productId);
        return bindDates(statement,dateFrom,dateTo);
    }
    private String reportDateFilters(String column,LocalDate dateFrom,LocalDate dateTo){return (dateFrom==null?"":" and ("+column+" at time zone 'Asia/Ho_Chi_Minh')::date>=:dateFrom")+(dateTo==null?"":" and ("+column+" at time zone 'Asia/Ho_Chi_Minh')::date<=:dateTo");}
    private JdbcClient.StatementSpec bindDates(JdbcClient.StatementSpec statement,LocalDate dateFrom,LocalDate dateTo){if(dateFrom!=null)statement=statement.param("dateFrom",dateFrom);if(dateTo!=null)statement=statement.param("dateTo",dateTo);return statement;}
    private Map<String,Object> user(UUID id){if(id==null)return Map.of();try{Map<String,Object> value=identity.user(id);return value==null?Map.of():value;}catch(RuntimeException ignored){return Map.of();}}
    private Object first(Object primary,Object secondary,Object fallback){if(primary!=null&&!String.valueOf(primary).isBlank())return primary;if(secondary!=null&&!String.valueOf(secondary).isBlank())return secondary;return fallback;}
    private Map<String,Object> meta(int page,int limit,long total){return map("page",page,"limit",limit,"total",total,"totalPages",total==0?0:(total+limit-1)/limit);}
    private static UUID uuid(ResultSet rs,String column)throws SQLException{return rs.getObject(column,UUID.class);}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
}
