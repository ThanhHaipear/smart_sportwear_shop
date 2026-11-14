package com.dunghaiquyen.sales.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SalesQueryRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public SalesQueryRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public long orderCount(UUID userId) {
        return jdbc.sql("select count(*) from orders where user_ref=:user").param("user", userId).query(Long.class).single();
    }

    public List<Map<String, Object>> orders(UUID userId, int limit, int offset) {
        return jdbc.sql("""
                select * from orders where user_ref=:user order by placed_at desc,id limit :limit offset :offset
                """).param("user", userId).param("limit", limit).param("offset", offset)
                .query((rs, row) -> order(rs)).list();
    }

    public Optional<Map<String, Object>> order(UUID userId, UUID orderId, boolean staff) {
        Optional<Map<String, Object>> order = jdbc.sql("""
                select * from orders where id=:id and (:staff=true or user_ref=:user)
                """).param("id", orderId).param("staff", staff).param("user", userId)
                .query((rs, row) -> order(rs)).optional();
        order.ifPresent(value -> value.put("items", orderItems(orderId)));
        return order;
    }

    public List<Map<String, Object>> orderItems(UUID orderId) {
        return jdbc.sql("""
                select id,product_ref,variant_ref,product_name_snapshot,sku_snapshot,size_snapshot,color_snapshot,
                       unit_price_snapshot,quantity,line_total from order_items where order_id=:id order by id
                """).param("id", orderId).query((rs, row) -> map(
                "id", uuid(rs,"id"), "productId", uuid(rs,"product_ref"), "variantId", uuid(rs,"variant_ref"),
                "productName", rs.getString("product_name_snapshot"), "sku", rs.getString("sku_snapshot"),
                "size", rs.getString("size_snapshot"), "color", rs.getString("color_snapshot"), "thumbnail", null,
                "unitPrice", rs.getBigDecimal("unit_price_snapshot"), "quantity", rs.getInt("quantity"),
                "lineTotal", rs.getBigDecimal("line_total"))).list();
    }

    public List<Map<String, Object>> payments(UUID userId, UUID orderId, boolean staff) {
        return jdbc.sql("""
                select p.* from payments p join orders o on o.id=p.order_id
                 where p.order_id=:order and (:staff=true or o.user_ref=:user) order by p.created_at desc
                """).param("order", orderId).param("staff", staff).param("user", userId)
                .query((rs, row) -> payment(rs)).list();
    }

    public Optional<Map<String, Object>> vietQr(UUID userId, UUID paymentId, String accountNumber, String bankBin, String accountName) {
        return jdbc.sql("""
                select p.*,o.user_ref from payments p join orders o on o.id=p.order_id
                 where p.id=:id and o.user_ref=:user and p.provider='BANK_TRANSFER'
                """).param("id", paymentId).param("user", userId).query((rs, row) -> map(
                "paymentId", uuid(rs,"id"), "orderId", uuid(rs,"order_id"),
                "transactionRef", rs.getString("transaction_ref"), "amount", rs.getBigDecimal("amount"),
                "currency", "VND", "bankBin", bankBin,
                "accountNumber", accountNumber, "accountName", accountName, "status", rs.getString("status"),
                "expiresAt", rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant())).optional();
    }

    public Optional<Map<String,Object>> vietQrByOrder(UUID userId,UUID orderId,String accountNumber,String bankBin,String accountName){
        return jdbc.sql("""
                select p.* from payments p join orders o on o.id=p.order_id
                 where p.order_id=:order and o.user_ref=:user and p.provider='BANK_TRANSFER'
                 order by p.created_at desc limit 1
                """).param("order",orderId).param("user",userId).query((rs,row)->map(
                "paymentId",uuid(rs,"id"),"orderId",uuid(rs,"order_id"),"transactionRef",rs.getString("transaction_ref"),
                "amount",rs.getBigDecimal("amount"),"currency","VND","bankBin",bankBin,"accountNumber",accountNumber,
                "accountName",accountName,"status",rs.getString("status"),"expiresAt",instant(rs,"expires_at"))).optional();
    }

    public long notificationCount(UUID userId) {
        return jdbc.sql("select count(*) from notifications where user_ref=:user").param("user",userId).query(Long.class).single();
    }

    public List<Map<String, Object>> notifications(UUID userId, int limit, int offset) {
        return jdbc.sql("""
                select * from notifications where user_ref=:user order by created_at desc,id limit :limit offset :offset
                """).param("user",userId).param("limit",limit).param("offset",offset)
                .query((rs,row)->notification(rs)).list();
    }

    public long unread(UUID userId) {
        return jdbc.sql("select count(*) from notifications where user_ref=:user and read_at is null")
                .param("user",userId).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        int updated=jdbc.sql("update notifications set read_at=coalesce(read_at,current_timestamp) where id=:id and user_ref=:user")
                .param("id",id).param("user",userId).update();
        if(updated==0) throw new IllegalArgumentException("Notification not found");
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return jdbc.sql("update notifications set read_at=current_timestamp where user_ref=:user and read_at is null")
                .param("user",userId).update();
    }

    public Map<String,Object> publicSettings() {
        Map<String,Object> result=new LinkedHashMap<>();
        jdbc.sql("select setting_key,setting_value,value_type from site_settings where is_public=true order by setting_key")
                .query().listOfRows().forEach(row->result.put(String.valueOf(row.get("setting_key")),row.get("setting_value")));
        return result;
    }

    public List<Map<String,Object>> refunds(UUID userId, UUID orderId) {
        return jdbc.sql("""
                select r.* from refunds r join orders o on o.id=r.order_id
                 where r.order_id=:order and o.user_ref=:user order by r.created_at desc
                """).param("order",orderId).param("user",userId).query().listOfRows();
    }

    @Transactional public Map<String,Object> invoice(UUID userId,UUID orderId){
        Map<String,Object> current=jdbc.sql("select payment_status,order_status from orders where id=:id and user_ref=:user for update").param("id",orderId).param("user",userId).query().singleRow();
        if(!"PAID".equals(current.get("payment_status"))||"CANCELLED".equals(current.get("order_status")))throw new IllegalStateException("Order is not invoice eligible");
        jdbc.sql("update orders set invoice_number=coalesce(invoice_number,'INV-'||lpad(nextval('orders_invoice_number_seq')::text,10,'0')),updated_at=current_timestamp where id=:id").param("id",orderId).update();
        return order(userId,orderId,false).orElseThrow(()->new IllegalArgumentException("Order not found"));
    }

    private Map<String,Object> order(ResultSet rs) throws SQLException {
        Map<String,Object> result=map(
                "id",uuid(rs,"id"),"orderCode",rs.getString("order_code"),"customerId",uuid(rs,"user_ref"),"orderStatus",rs.getString("order_status"),
                "paymentStatus",rs.getString("payment_status"),"paymentMethod",rs.getString("payment_method"),
                "subtotalAmount",rs.getBigDecimal("subtotal_amount"),"shippingFee",rs.getBigDecimal("shipping_fee"),
                "discountAmount",rs.getBigDecimal("discount_amount"),"totalAmount",rs.getBigDecimal("total_amount"),
                "note",rs.getString("note"),"internalNote",rs.getString("internal_note"),"cancellationRequestedBy",rs.getString("cancellation_requested_by"),
                "cancellationReason",rs.getString("cancellation_reason"),
                "cancellationRequestedAt",instant(rs,"cancellation_requested_at"),
                "shippingAddress",jsonMap(rs.getString("address_snapshot_json")),"invoiceNumber",rs.getString("invoice_number"),
                "invoiceRequested",rs.getString("invoice_number")!=null,"invoiceCompanyName",null,"invoiceTaxCode",null,
                "invoiceCompanyAddress",null,"createdAt",instant(rs,"created_at"),"items",List.of());
        return result;
    }

    private Map<String,Object> payment(ResultSet rs) throws SQLException { return map(
            "id",uuid(rs,"id"),"provider",rs.getString("provider"),"transactionRef",rs.getString("transaction_ref"),
            "amount",rs.getBigDecimal("amount"),"status",rs.getString("status"),
            "gatewayTransactionNo",rs.getString("gateway_transaction_no"),"bankCode",rs.getString("bank_code"),
            "paidAt",instant(rs,"paid_at"),"createdAt",instant(rs,"created_at")); }

    private Map<String,Object> notification(ResultSet rs) throws SQLException { return map(
            "id",uuid(rs,"id"),"userId",uuid(rs,"user_ref"),"orderId",uuid(rs,"order_id"),
            "type",rs.getString("type"),"channel",rs.getString("channel"),"recipient",rs.getString("recipient"),
            "subject",rs.getString("subject"),"body",rs.getString("body"),"status",rs.getString("status"),
            "errorMessage",rs.getString("error_message"),"createdAt",instant(rs,"created_at"),"sentAt",instant(rs,"sent_at"),
            "readAt",instant(rs,"read_at"),"resendOfId",uuid(rs,"resend_of_id"),"resendCount",rs.getInt("resend_count"),
            "lastResendAt",instant(rs,"last_resend_at")); }

    private Map<String,Object> jsonMap(String raw) {
        if(raw==null||raw.isBlank()) return new LinkedHashMap<>();
        try{return new LinkedHashMap<>(json.readValue(raw,JSON_MAP));}catch(Exception ignored){return new LinkedHashMap<>();}
    }
    private static Object instant(ResultSet rs,String column)throws SQLException{var value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private static UUID uuid(ResultSet rs,String column)throws SQLException{return rs.getObject(column,UUID.class);}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
}
