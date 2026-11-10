package com.dunghaiquyen.sales.persistence;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import com.dunghaiquyen.sales.domain.SalesRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSalesRepository implements SalesRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public JdbcSalesRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Override @Transactional public OrderResponse createOrder(OrderAggregate o){
        jdbc.update("insert into orders(id,order_code,user_ref,reservation_ref,address_snapshot_json,subtotal_amount,shipping_fee,discount_amount,total_amount,payment_method,order_status,payment_status,note) values(?,?,?,?,?::jsonb,?,?,0,?,?,?,?,?)",
                o.id(),o.orderCode(),o.userId(),o.reservationId(),toJson(o.address()),o.subtotal(),o.shippingFee(),o.total(),o.paymentMethod().name(),"PENDING_CONFIRMATION",o.paymentMethod()==PaymentMethod.VIETQR?"PENDING":"UNPAID",o.note());
        for(OrderLine i:o.items()) jdbc.update("insert into order_items(id,order_id,product_ref,variant_ref,product_name_snapshot,sku_snapshot,size_snapshot,color_snapshot,unit_price_snapshot,quantity,line_total,discount_allocated,net_line_amount) values(?,?,?,?,?,?,?,?,?,?,?,0,?)",
                i.id(),o.id(),i.productId(),i.variantId(),i.productName(),i.sku(),i.size(),i.color(),i.unitPrice(),i.quantity(),i.total(),i.total());
        String provider=o.paymentMethod()==PaymentMethod.VIETQR?"BANK_TRANSFER":"COD"; String status=o.paymentMethod()==PaymentMethod.VIETQR?"PENDING":"UNPAID";
        jdbc.update("insert into payments(id,order_id,provider,transaction_ref,amount,status,expires_at) values(?,?,?,?,?,?,?)",o.paymentId(),o.id(),provider,o.transactionRef(),o.total(),status,ts(o.paymentExpiresAt()));
        jdbc.update("insert into order_status_history(order_id,to_status,reason) values(?,?,?)",o.id(),"PENDING_CONFIRMATION","ORDER_CREATED");
        outbox("sales.order.created.v1","Order",o.id(),Map.of("eventId",UUID.randomUUID(),"eventType","ORDER_CREATED","eventVersion",1,
                "occurredAt",Instant.now().toString(),"traceId","sales-"+o.id(),"orderId",o.id(),"userRef",o.userId().toString(),
                "items",o.items().stream().map(OrderLine::variantId).toList()));
        return new OrderResponse(o.id(),o.orderCode(),"PENDING_CONFIRMATION",status,o.paymentMethod(),o.total(),o.transactionRef(),o.paymentExpiresAt());
    }
    @Override @Transactional public void cancelOrderAfterReservationFailure(UUID orderId,UUID paymentId,String reason){
        jdbc.update("update orders set order_status='CANCELLED',payment_status='CANCELLED',cancellation_requested_by='SYSTEM',cancellation_reason=?,cancellation_requested_at=now(),updated_at=now() where id=? and order_status='PENDING_CONFIRMATION'",reason,orderId);
        jdbc.update("update payments set status='CANCELLED',updated_at=now() where id=? and status in ('UNPAID','PENDING')",paymentId);
        jdbc.update("insert into order_status_history(order_id,from_status,to_status,reason) values(?,'PENDING_CONFIRMATION','CANCELLED',?)",orderId,reason);
        outbox("sales.order.cancelled.v1","Order",orderId,Map.of("eventId",UUID.randomUUID(),"orderId",orderId,"reason",reason,"occurredAt",Instant.now().toString()));
    }
    @Override public Optional<IngestionResult> findIngestion(String externalId){
        jdbc.execute((ConnectionCallback<Void>) connection->{try(PreparedStatement statement=connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){statement.setString(1,"SEPAY:WEBHOOK:"+externalId);statement.execute();}return null;});
        List<IngestionResult> rows=jdbc.query("select id,match_status from bank_transactions where source='SEPAY' and external_transaction_id=?",(rs,n)->new IngestionResult(rs.getObject(1,UUID.class),MatchStatus.valueOf(rs.getString(2)),true),externalId);
        return rows.stream().findFirst();
    }
    @Override public UUID insertBankTransaction(String externalId,String bankReference,String accountNumber,BigDecimal amount,String description,Instant time,BigDecimal balance,Map<String,Object> payload){
        UUID id=UUID.randomUUID();
        jdbc.update("insert into bank_transactions(id,external_transaction_id,bank_reference,account_number,amount,description,transaction_time,running_balance,raw_payload_redacted) values(?,?,?,?,?,?,?,?,?::jsonb)",id,externalId,bankReference,accountNumber,amount,description,Timestamp.from(time),balance,toJson(payload));
        return id;
    }
    @Override public Optional<PaymentMatch> lockPayment(String ref){
        List<PaymentMatch> rows=jdbc.query("select p.id,p.order_id,p.amount,p.status,o.order_status,o.payment_status,p.expires_at from payments p join orders o on o.id=p.order_id where p.provider='BANK_TRANSFER' and p.transaction_ref=? for update of p,o",
                (rs,n)->new PaymentMatch(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBigDecimal(3),rs.getString(4),rs.getString(5),rs.getString(6),instant(rs.getTimestamp(7))),ref); return rows.stream().findFirst();
    }
    @Override public Optional<RefundMatch> lockRefund(String code){
        List<RefundMatch> rows=jdbc.query("select r.id,r.payment_id,r.order_id,r.amount,r.status,(r.refund_bank_code is not null and r.refund_bank_name is not null and r.refund_account_number is not null and r.refund_account_name is not null) from refunds r where r.refund_code=? for update",
                (rs,n)->new RefundMatch(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBigDecimal(4),rs.getString(5),rs.getBoolean(6)),code); return rows.stream().findFirst();
    }
    @Override public void matchPayment(UUID tx,PaymentMatch p,SePayRequest r,Instant at){
        jdbc.update("update payments set status='PAID',paid_at=?,gateway_transaction_no=?,bank_code=?,updated_at=? where id=?",ts(at),r.referenceCode(),r.gateway(),ts(at),p.paymentId());
        jdbc.update("update orders set payment_status='PAID',updated_at=? where id=?",ts(at),p.orderId());
        jdbc.update("update bank_transactions set payment_id=?,match_status='MATCHED',match_reason='EXACT_REFERENCE_ACCOUNT_AMOUNT_MATCH',reconciled_at=? where id=?",p.paymentId(),ts(at),tx);
        paymentEvent(p.paymentId(),r,"BANK_CREDIT_MATCHED");
        outbox("sales.payment.completed.v1","Payment",p.paymentId(),Map.of("eventId",UUID.randomUUID(),"paymentId",p.paymentId(),"orderId",p.orderId(),"amount",p.amount(),"provider","SEPAY","paidAt",at.toString()));
    }
    @Override public void completeRefund(UUID tx,RefundMatch r,SePayRequest request,Instant at){
        jdbc.update("update refunds set status='COMPLETED',refunded_at=?,manual_reference=?,updated_at=? where id=?",ts(at),request.referenceCode(),ts(at),r.refundId());
        BigDecimal completed=jdbc.queryForObject("select coalesce(sum(amount),0) from refunds where order_id=? and status='COMPLETED'",BigDecimal.class,r.orderId());
        BigDecimal paid=jdbc.queryForObject("select amount from payments where id=?",BigDecimal.class,r.paymentId());
        String status=completed.compareTo(paid)>=0?"REFUNDED":"PARTIALLY_REFUNDED";
        jdbc.update("update payments set status=?,updated_at=? where id=?",status,ts(at),r.paymentId()); jdbc.update("update orders set payment_status=?,updated_at=? where id=?",status,ts(at),r.orderId());
        jdbc.update("update bank_transactions set payment_id=?,refund_id=?,match_status='MATCHED',match_reason='EXACT_REFUND_REFERENCE_ACCOUNT_AMOUNT_MATCH',reconciled_at=? where id=?",r.paymentId(),r.refundId(),ts(at),tx);
        paymentEvent(r.paymentId(),request,"BANK_DEBIT_REFUND_MATCHED");
        outbox("sales.refund.completed.v1","Refund",r.refundId(),Map.of("eventId",UUID.randomUUID(),"refundId",r.refundId(),"orderId",r.orderId(),"amount",r.amount(),"completedAt",at.toString()));
    }
    @Override public void rejectTransaction(UUID tx,MatchStatus status,String reason,UUID paymentId,UUID refundId){jdbc.update("update bank_transactions set payment_id=?,refund_id=?,match_status=?,match_reason=? where id=?",paymentId,refundId,status.name(),reason,tx);}
    @Override @Transactional public RefundResponse createRefund(CreateRefundRequest r,UUID actor){
        PaymentMatch payment=jdbc.queryForObject("select p.id,p.order_id,p.amount,p.status,o.order_status,o.payment_status,p.expires_at from payments p join orders o on o.id=p.order_id where p.id=? and p.order_id=? for update of p,o",
                (rs,n)->new PaymentMatch(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBigDecimal(3),rs.getString(4),rs.getString(5),rs.getString(6),instant(rs.getTimestamp(7))),r.paymentId(),r.orderId());
        if(payment==null||!("PAID".equals(payment.status())||"PARTIALLY_REFUNDED".equals(payment.status())))throw new IllegalArgumentException("Only a paid payment can be refunded");
        BigDecimal prior=jdbc.queryForObject("select coalesce(sum(amount),0) from refunds where payment_id=? and status in ('PENDING','PROCESSING','COMPLETED')",BigDecimal.class,r.paymentId());
        if(prior.add(r.amount()).compareTo(payment.amount())>0)throw new IllegalArgumentException("Refund exceeds captured amount");
        UUID id=UUID.randomUUID();String code="RFD"+String.format("%013d",Math.floorMod(id.getMostSignificantBits(),10_000_000_000_000L));
        jdbc.update("insert into refunds(id,order_id,payment_id,refund_code,amount,provider,status,reason,refund_bank_code,refund_bank_name,refund_account_number,refund_account_name,created_by_ref) values(?,?,?,?,?,'MANUAL','PENDING',?,?,?,?,?,?)",
                id,r.orderId(),r.paymentId(),code,r.amount(),r.reason(),r.bankCode(),r.bankName(),r.accountNumber(),r.accountName(),actor);
        return new RefundResponse(id,code,r.amount(),"PENDING");
    }
    private void paymentEvent(UUID payment,SePayRequest r,String type){jdbc.update("insert into payment_events(payment_id,provider,provider_event_key,event_type,verified,payload_redacted) values(?,'BANK_TRANSFER',?,?,true,?::jsonb) on conflict(provider,provider_event_key) do nothing",payment,sha256("SEPAY|"+r.id()),type,toJson(Map.of("gateway",r.gateway(),"transferType",r.transferType())));}
    private void outbox(String type,String aggregate,UUID id,Object payload){jdbc.update("insert into integration_outbox(event_type,aggregate_type,aggregate_id,payload) values(?,?,?,?::jsonb)",type,aggregate,id,toJson(payload));}
    private String toJson(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("Cannot serialize database payload",e);}}
    private String sha256(String s){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);} private Instant instant(Timestamp t){return t==null?null:t.toInstant();}
}
