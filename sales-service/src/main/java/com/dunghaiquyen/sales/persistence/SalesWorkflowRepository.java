package com.dunghaiquyen.sales.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Phase 11 write model for cancellation, returns and manual refunds. */
@Repository
public class SalesWorkflowRepository {
    private final JdbcClient jdbc;
    public SalesWorkflowRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    public record RefundDestination(String bankCode,String bankName,String accountNumber,String accountName){}
    public record CancelRequest(String reason,String bankCode,String bankName,String accountNumber,String accountName){}
    public record ReturnItemRequest(UUID orderItemId,int quantity,String reason){}
    public record CreateReturnRequest(UUID orderId,String reason,String description,List<ReturnItemRequest> items){}
    public record Resolution(UUID returnItemId,String conditionStatus,String resolution,BigDecimal refundAmount){}

    @Transactional
    public Map<String,Object> cancelOrder(UUID orderId,UUID userId,CancelRequest request){
        Map<String,Object> order=jdbc.sql("select * from orders where id=:id and user_ref=:user for update")
                .param("id",orderId).param("user",userId).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Order not found"));
        String status=String.valueOf(order.get("order_status"));
        if(status.equals("DELIVERED")||status.equals("CANCELLED"))throw new IllegalStateException("Order cannot be cancelled in status "+status);
        String payment=String.valueOf(order.get("payment_status"));
        jdbc.sql("update orders set order_status='CANCELLED',payment_status=case when payment_status in ('UNPAID','PENDING') then 'CANCELLED' else payment_status end,cancellation_requested_by='CUSTOMER',cancellation_reason=:reason,cancellation_requested_at=current_timestamp,updated_at=current_timestamp where id=:id")
                .param("reason",request==null?null:request.reason()).param("id",orderId).update();
        jdbc.sql("insert into order_status_history(order_id,from_status,to_status,reason,actor_ref) values(:id,:before,'CANCELLED',:reason,:actor)")
                .param("id",orderId).param("before",status).param("reason",request==null?null:request.reason()).param("actor",userId).update();
        if(payment.equals("PAID")||payment.equals("PARTIALLY_REFUNDED"))createCancellationRefund(orderId,userId,request);
        jdbc.sql("insert into integration_outbox(event_type,aggregate_type,aggregate_id,payload) values('sales.order.cancelled.v1','order',:id,jsonb_build_object('orderId',:id,'reason',:reason))")
                .param("id",orderId).param("reason",request==null?null:request.reason()).update();
        return order(userId,orderId);
    }

    @Transactional public Map<String,Object> cancelOrderByStaff(UUID orderId,UUID actor,CancelRequest request){
        UUID owner=jdbc.sql("select user_ref from orders where id=:id").param("id",orderId).query(UUID.class).optional().orElseThrow(()->new IllegalArgumentException("Order not found"));
        Map<String,Object> result=cancelOrder(orderId,owner,request);
        jdbc.sql("update orders set cancellation_requested_by='STAFF' where id=:id").param("id",orderId).update();
        return result;
    }

    private void createCancellationRefund(UUID orderId,UUID actor,CancelRequest request){
        Map<String,Object> payment=jdbc.sql("select id,amount from payments where order_id=:id and status in ('PAID','PARTIALLY_REFUNDED') order by paid_at desc nulls last limit 1")
                .param("id",orderId).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalStateException("Paid order has no payment"));
        UUID id=UUID.randomUUID(); String code=refundCode(id);
        jdbc.sql("""
            insert into refunds(id,order_id,payment_id,refund_code,amount,provider,status,reason,refund_bank_code,refund_bank_name,refund_account_number,refund_account_name,created_by_ref)
            values(:id,:order,:payment,:code,:amount,'MANUAL','PENDING','ORDER_CANCELLED',:bankCode,:bankName,:accountNumber,:accountName,:actor)
            """).param("id",id).param("order",orderId).param("payment",payment.get("id")).param("code",code)
                .param("amount",payment.get("amount")).param("bankCode",request==null?null:request.bankCode())
                .param("bankName",request==null?null:request.bankName()).param("accountNumber",request==null?null:request.accountNumber())
                .param("accountName",request==null?null:request.accountName()).param("actor",actor).update();
    }

    @Transactional
    public Map<String,Object> updateRefundDestination(UUID orderId,UUID userId,RefundDestination destination){
        UUID id=jdbc.sql("select r.id from refunds r join orders o on o.id=r.order_id where r.order_id=:order and o.user_ref=:user and r.status in ('PENDING','FAILED') order by r.created_at desc limit 1 for update of r")
                .param("order",orderId).param("user",userId).query(UUID.class).optional().orElseThrow(()->new IllegalArgumentException("Pending refund not found"));
        jdbc.sql("update refunds set refund_bank_code=:code,refund_bank_name=:name,refund_account_number=:number,refund_account_name=:account,updated_at=current_timestamp where id=:id")
                .param("code",destination.bankCode()).param("name",destination.bankName()).param("number",destination.accountNumber()).param("account",destination.accountName()).param("id",id).update();
        return refund(id);
    }

    @Transactional
    public Map<String,Object> createReturn(UUID userId,CreateReturnRequest request){
        if(request.items()==null||request.items().isEmpty())throw new IllegalArgumentException("Return items are required");
        Map<String,Object> order=jdbc.sql("select id,order_status,return_eligible_until from orders where id=:id and user_ref=:user for update")
                .param("id",request.orderId()).param("user",userId).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Order not found"));
        if(!"DELIVERED".equals(order.get("order_status")))throw new IllegalStateException("Only delivered orders can be returned");
        Object eligible=order.get("return_eligible_until"); if(eligible instanceof java.sql.Timestamp t&&t.toInstant().isBefore(Instant.now()))throw new IllegalStateException("Return window has expired");
        UUID returnId=UUID.randomUUID();
        jdbc.sql("insert into returns(id,order_id,user_ref,return_code,status,reason,description) values(:id,:order,:user,:code,'REQUESTED',:reason,:description)")
                .param("id",returnId).param("order",request.orderId()).param("user",userId).param("code","RET"+returnId.toString().replace("-","").substring(0,12).toUpperCase())
                .param("reason",request.reason()).param("description",request.description()).update();
        for(ReturnItemRequest item:request.items()){
            if(item.quantity()<1)throw new IllegalArgumentException("Return quantity must be positive");
            Map<String,Object> line=jdbc.sql("select quantity,return_pending_quantity,returned_quantity from order_items where id=:item and order_id=:order for update")
                    .param("item",item.orderItemId()).param("order",request.orderId()).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Order item not found"));
            int available=((Number)line.get("quantity")).intValue()-((Number)line.get("return_pending_quantity")).intValue()-((Number)line.get("returned_quantity")).intValue();
            if(item.quantity()>available)throw new IllegalStateException("Return quantity exceeds available quantity");
            jdbc.sql("insert into return_items(id,return_id,order_id,order_item_id,quantity,reason) values(:id,:returnId,:order,:item,:quantity,:reason)")
                    .param("id",UUID.randomUUID()).param("returnId",returnId).param("order",request.orderId()).param("item",item.orderItemId()).param("quantity",item.quantity()).param("reason",item.reason()).update();
            jdbc.sql("update order_items set return_pending_quantity=return_pending_quantity+:quantity where id=:id").param("quantity",item.quantity()).param("id",item.orderItemId()).update();
        }
        return returnDetail(returnId,userId,false);
    }

    public List<Map<String,Object>> myReturns(UUID userId){return jdbc.sql("select id from returns where user_ref=:user order by requested_at desc").param("user",userId).query(UUID.class).list().stream().map(id->returnDetail(id,userId,false)).toList();}

    @Transactional public Map<String,Object> cancelReturn(UUID id,UUID userId){
        String status=jdbc.sql("select status from returns where id=:id and user_ref=:user for update").param("id",id).param("user",userId).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Return not found"));
        if(!"REQUESTED".equals(status))throw new IllegalStateException("Only requested returns can be cancelled");
        jdbc.sql("update order_items oi set return_pending_quantity=greatest(0,oi.return_pending_quantity-ri.quantity) from return_items ri where ri.return_id=:id and oi.id=ri.order_item_id").param("id",id).update();
        jdbc.sql("update returns set status='CANCELLED',resolved_at=current_timestamp,updated_at=current_timestamp where id=:id").param("id",id).update();
        return returnDetail(id,userId,false);
    }

    @Transactional public Map<String,Object> updateReturnStatus(UUID id,String next,List<Resolution> resolutions,UUID actor){
        String current=jdbc.sql("select status from returns where id=:id for update").param("id",id).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Return not found"));
        Map<String,List<String>> allowed=Map.of("REQUESTED",List.of("APPROVED","REJECTED","CANCELLED"),"APPROVED",List.of("RECEIVED"),"RECEIVED",List.of("REFUNDED"));
        if(!allowed.getOrDefault(current,List.of()).contains(next))throw new IllegalStateException("Invalid return transition "+current+" -> "+next);
        if(next.equals("RECEIVED")){
            if(resolutions==null||resolutions.isEmpty())throw new IllegalArgumentException("Item resolutions are required when receiving a return");
            for(Resolution item:resolutions)jdbc.sql("update return_items set condition_status=:condition,resolution=:resolution,refund_amount=:amount where id=:item and return_id=:return")
                    .param("condition",item.conditionStatus()).param("resolution",item.resolution()).param("amount",item.refundAmount()).param("item",item.returnItemId()).param("return",id).update();
            jdbc.sql("update order_items oi set return_pending_quantity=greatest(0,oi.return_pending_quantity-ri.quantity),returned_quantity=oi.returned_quantity+ri.quantity from return_items ri where ri.return_id=:id and oi.id=ri.order_item_id").param("id",id).update();
        } else if(next.equals("REJECTED")||next.equals("CANCELLED")) jdbc.sql("update order_items oi set return_pending_quantity=greatest(0,oi.return_pending_quantity-ri.quantity) from return_items ri where ri.return_id=:id and oi.id=ri.order_item_id").param("id",id).update();
        jdbc.sql("update returns set status=:status,handled_by_ref=:actor,approved_at=case when :status='APPROVED' then current_timestamp else approved_at end,received_at=case when :status='RECEIVED' then current_timestamp else received_at end,resolved_at=case when :status in ('REJECTED','CANCELLED','REFUNDED') then current_timestamp else resolved_at end,updated_at=current_timestamp where id=:id")
                .param("status",next).param("actor",actor).param("id",id).update();
        return returnDetail(id,null,true);
    }

    @Transactional public Map<String,Object> createRefundForReturn(UUID returnId,String reason,UUID actor){
        Map<String,Object> returned=jdbc.sql("select order_id,status from returns where id=:id for update").param("id",returnId).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Return not found"));
        if(!"RECEIVED".equals(returned.get("status")))throw new IllegalStateException("Return must be received before refund");
        var existing=jdbc.sql("select id from refunds where return_id=:id and status not in ('CANCELLED') order by created_at desc limit 1").param("id",returnId).query(UUID.class).optional();
        if(existing.isPresent())return refund(existing.get());
        BigDecimal amount=jdbc.sql("select coalesce(sum(refund_amount),0) from return_items where return_id=:id and resolution='REFUND'").param("id",returnId).query(BigDecimal.class).single();
        if(amount.signum()<=0)throw new IllegalStateException("Return has no refundable amount");
        UUID orderId=(UUID)returned.get("order_id"); UUID payment=jdbc.sql("select id from payments where order_id=:id and status in ('PAID','PARTIALLY_REFUNDED') order by paid_at desc nulls last limit 1").param("id",orderId).query(UUID.class).optional().orElseThrow(()->new IllegalStateException("Paid payment not found"));
        UUID id=UUID.randomUUID(); jdbc.sql("insert into refunds(id,return_id,order_id,payment_id,refund_code,amount,provider,status,reason,created_by_ref) values(:id,:returnId,:order,:payment,:code,:amount,'MANUAL','PENDING',:reason,:actor)")
                .param("id",id).param("returnId",returnId).param("order",orderId).param("payment",payment).param("code",refundCode(id)).param("amount",amount).param("reason",reason).param("actor",actor).update();
        return refund(id);
    }

    @Transactional public Map<String,Object> updateRefundStatus(UUID id,String status,UUID actor){
        if(!List.of("PENDING","PROCESSING","COMPLETED","FAILED","CANCELLED").contains(status))throw new IllegalArgumentException("Invalid refund status");
        String current=jdbc.sql("select status from refunds where id=:id for update").param("id",id).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Refund not found"));
        if(current.equals(status))return refund(id);
        Map<String,List<String>> allowed=Map.of("PENDING",List.of("PROCESSING","COMPLETED","FAILED","CANCELLED"),"PROCESSING",List.of("COMPLETED","FAILED"),"FAILED",List.of("PROCESSING","COMPLETED","CANCELLED"));
        if(!allowed.getOrDefault(current,List.of()).contains(status))throw new IllegalStateException("Invalid refund transition "+current+" -> "+status);
        jdbc.sql("update refunds set status=:status,refunded_at=case when :status='COMPLETED' then current_timestamp else refunded_at end,updated_at=current_timestamp where id=:id").param("status",status).param("id",id).update();
        if(status.equals("COMPLETED"))completeRefundAggregate(id,actor);
        return refund(id);
    }

    @Transactional public Map<String,Object> confirmManualRefund(UUID id,String reference,String note,UUID actor){
        if(reference==null||reference.isBlank())throw new IllegalArgumentException("Manual reference is required");
        String current=jdbc.sql("select status from refunds where id=:id for update").param("id",id).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Refund not found"));
        if(current.equals("COMPLETED"))return refund(id);
        if(!List.of("PENDING","PROCESSING","FAILED").contains(current))throw new IllegalStateException("Refund cannot be manually confirmed in status "+current);
        jdbc.sql("update refunds set status='COMPLETED',manual_reference=:reference,manual_note=:note,refunded_at=current_timestamp,updated_at=current_timestamp where id=:id and status in ('PENDING','PROCESSING','FAILED')")
                .param("reference",reference).param("note",note).param("id",id).update(); completeRefundAggregate(id,actor); return refund(id);
    }

    private void completeRefundAggregate(UUID refundId,UUID actor){
        Map<String,Object> r=jdbc.sql("select order_id,return_id,amount from refunds where id=:id").param("id",refundId).query().singleRow(); UUID order=(UUID)r.get("order_id");
        BigDecimal paid=jdbc.sql("select coalesce(sum(amount),0) from payments where order_id=:id and status in ('PAID','PARTIALLY_REFUNDED','REFUNDED')").param("id",order).query(BigDecimal.class).single();
        BigDecimal refunded=jdbc.sql("select coalesce(sum(amount),0) from refunds where order_id=:id and status='COMPLETED'").param("id",order).query(BigDecimal.class).single();
        String paymentStatus=refunded.compareTo(paid)>=0?"REFUNDED":"PARTIALLY_REFUNDED";
        jdbc.sql("update orders set payment_status=:status,updated_at=current_timestamp where id=:id").param("status",paymentStatus).param("id",order).update();
        jdbc.sql("update payments set status=:status,updated_at=current_timestamp where order_id=:id and status in ('PAID','PARTIALLY_REFUNDED','REFUNDED')").param("status",paymentStatus).param("id",order).update();
        if(r.get("return_id")!=null)jdbc.sql("update returns set status='REFUNDED',resolved_at=current_timestamp,handled_by_ref=:actor,updated_at=current_timestamp where id=:id").param("actor",actor).param("id",r.get("return_id")).update();
        jdbc.sql("insert into integration_outbox(event_type,aggregate_type,aggregate_id,payload) values('sales.refund.completed.v1','refund',:id,jsonb_build_object('refundId',:id,'orderId',:order,'amount',:amount))")
                .param("id",refundId).param("order",order).param("amount",r.get("amount")).update();
    }

    public Map<String,Object> order(UUID user,UUID id){return jdbc.sql("select * from orders where id=:id and user_ref=:user").param("id",id).param("user",user).query().singleRow();}
    public Map<String,Object> returnDetail(UUID id,UUID user,boolean staff){
        Map<String,Object> row=jdbc.sql("select r.*,o.order_code from returns r join orders o on o.id=r.order_id where r.id=:id and (:staff or r.user_ref=:user)").param("id",id).param("staff",staff).param("user",user).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Return not found"));
        Map<String,Object> result=camelReturn(row); result.put("items",jdbc.sql("select ri.*,oi.product_name_snapshot,oi.sku_snapshot from return_items ri join order_items oi on oi.id=ri.order_item_id where ri.return_id=:id order by ri.created_at,ri.id").param("id",id).query((rs,n)->map("id",rs.getObject("id",UUID.class),"orderItemId",rs.getObject("order_item_id",UUID.class),"productName",rs.getString("product_name_snapshot"),"sku",rs.getString("sku_snapshot"),"quantity",rs.getInt("quantity"),"reason",rs.getString("reason"),"conditionStatus",rs.getString("condition_status"),"resolution",rs.getString("resolution"),"refundAmount",rs.getBigDecimal("refund_amount"))).list()); return result;
    }
    public List<Map<String,Object>> refundsForReturn(UUID id){return jdbc.sql("select id from refunds where return_id=:id order by created_at desc").param("id",id).query(UUID.class).list().stream().map(this::refund).toList();}
    public Map<String,Object> refund(UUID id){Map<String,Object> r=jdbc.sql("select * from refunds where id=:id").param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Refund not found"));return map("id",r.get("id"),"returnId",r.get("return_id"),"orderId",r.get("order_id"),"paymentId",r.get("payment_id"),"refundCode",r.get("refund_code"),"amount",r.get("amount"),"provider",r.get("provider"),"status",r.get("status"),"gatewayRequestId",r.get("gateway_request_id"),"gatewayTransactionNo",r.get("gateway_transaction_no"),"manualReference",r.get("manual_reference"),"manualNote",r.get("manual_note"),"refundBankCode",r.get("refund_bank_code"),"refundBankName",r.get("refund_bank_name"),"refundAccountNumber",r.get("refund_account_number"),"refundAccountName",r.get("refund_account_name"),"reason",r.get("reason"),"refundedAt",r.get("refunded_at"),"createdAt",r.get("created_at"),"updatedAt",r.get("updated_at"));}
    private Map<String,Object> camelReturn(Map<String,Object> r){return new LinkedHashMap<>(map("id",r.get("id"),"orderId",r.get("order_id"),"orderCode",r.get("order_code"),"userId",r.get("user_ref"),"returnCode",r.get("return_code"),"status",r.get("status"),"reason",r.get("reason"),"description",r.get("description"),"requestedAt",r.get("requested_at"),"approvedAt",r.get("approved_at"),"receivedAt",r.get("received_at"),"resolvedAt",r.get("resolved_at"),"createdAt",r.get("created_at"),"updatedAt",r.get("updated_at")));}
    private static String refundCode(UUID id){return String.format("RFD%013d",Math.floorMod(id.getMostSignificantBits()^id.getLeastSignificantBits(),10_000_000_000_000L));}
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
