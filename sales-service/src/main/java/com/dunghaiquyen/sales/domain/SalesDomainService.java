package com.dunghaiquyen.sales.domain;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import com.dunghaiquyen.sales.catalog.CatalogClient;
import com.dunghaiquyen.sales.config.SalesProperties;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesDomainService {
    private static final Pattern PAYMENT_REF=Pattern.compile("(?<![A-Z0-9])DH[A-Z0-9]{10}(?![A-Z0-9])");
    private static final Pattern REFUND_REF=Pattern.compile("(?<![A-Z0-9])RFD[0-9]{13}(?![A-Z0-9])");
    private final SalesRepository repository; private final CatalogClient catalog; private final SalesProperties properties; private final Clock clock;
    public SalesDomainService(SalesRepository repository,CatalogClient catalog,SalesProperties properties){this(repository,catalog,properties,Clock.systemUTC());}
    SalesDomainService(SalesRepository repository,CatalogClient catalog,SalesProperties properties,Clock clock){this.repository=repository;this.catalog=catalog;this.properties=properties;this.clock=clock;}

    public OrderResponse createOrder(CreateOrderRequest request){
        UUID orderId=UUID.randomUUID(); List<OrderLine> lines=new ArrayList<>();
        for(OrderLineRequest item:request.items()){
            VariantSnapshot s=catalog.snapshot(item.variantId());
            if(!s.active()||s.availableQuantity()<item.quantity()) throw new IllegalArgumentException("Variant is not saleable or has insufficient inventory: "+item.variantId());
            lines.add(new OrderLine(UUID.randomUUID(),s.productId(),s.variantId(),s.sku(),s.productName(),s.size(),s.color(),s.price(),item.quantity()));
        }
        Reservation reservation=catalog.reserve(orderId,request.items());
        try{
            BigDecimal subtotal=lines.stream().map(OrderLine::total).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal shipping=request.shippingFee()==null?BigDecimal.ZERO:request.shippingFee();
            UUID paymentId=UUID.randomUUID(); String ref=request.paymentMethod()==PaymentMethod.VIETQR?paymentReference(orderId):"COD-"+orderId;
            Instant expires=request.paymentMethod()==PaymentMethod.VIETQR?clock.instant().plusSeconds(properties.vietqr().paymentTtlSeconds()):null;
            OrderAggregate aggregate=new OrderAggregate(orderId,"ORD-"+orderId.toString().substring(0,8).toUpperCase(),request.userId(),reservation.id(),
                    request.paymentMethod(),subtotal,shipping,subtotal.add(shipping),lines,paymentId,ref,expires,request.address(),request.note());
            OrderResponse response=repository.createOrder(aggregate);
            try { catalog.confirm(reservation.id()); }
            catch (RuntimeException confirmationFailure) {
                repository.cancelOrderAfterReservationFailure(orderId,paymentId,"CATALOG_RESERVATION_CONFIRM_FAILED");
                throw confirmationFailure;
            }
            return response;
        }catch(RuntimeException e){try{catalog.release(reservation.id());}catch(RuntimeException ignored){} throw e;}
    }

    @Transactional public IngestionResult ingest(SePayRequest request){
        String externalId=request.id().toString(); Optional<IngestionResult> duplicate=repository.findIngestion(externalId); if(duplicate.isPresent()) return duplicate.get();
        BigDecimal amount=request.transferAmount().abs().multiply("out".equals(request.transferType())?BigDecimal.valueOf(-1):BigDecimal.ONE);
        UUID tx=repository.insertBankTransaction(externalId,request.referenceCode(),request.accountNumber().trim(),amount,
                first(request.content(),request.description()),parseTime(request.transactionDate()),request.accumulated(),redacted(request));
        if(!Objects.equals(properties.vietqr().accountNumber(),request.accountNumber().trim())) return reject(tx,MatchStatus.MANUAL_REVIEW,"ACCOUNT_MISMATCH",null,null);
        if("out".equals(request.transferType())) return matchRefund(tx,request);
        String ref=extract(request,PAYMENT_REF); if(ref==null)return reject(tx,MatchStatus.UNMATCHED,"PAYMENT_REFERENCE_NOT_FOUND",null,null);
        Optional<PaymentMatch> candidate=repository.lockPayment(ref); if(candidate.isEmpty())return reject(tx,MatchStatus.UNMATCHED,"PAYMENT_NOT_FOUND",null,null);
        PaymentMatch p=candidate.get();
        if(p.amount().compareTo(amount)!=0)return reject(tx,MatchStatus.AMOUNT_MISMATCH,"AMOUNT_MISMATCH",p.paymentId(),null);
        if(p.expiresAt()==null||!p.expiresAt().isAfter(clock.instant())||"CANCELLED".equals(p.orderStatus())||"CANCELLED".equals(p.status()))
            return reject(tx,MatchStatus.LATE_PAYMENT,"PAYMENT_EXPIRED_OR_CANCELLED",p.paymentId(),null);
        if(!"PENDING".equals(p.status())||"PAID".equals(p.orderPaymentStatus()))return reject(tx,MatchStatus.MANUAL_REVIEW,"PAYMENT_NOT_MATCHABLE",p.paymentId(),null);
        repository.matchPayment(tx,p,request,clock.instant()); return new IngestionResult(tx,MatchStatus.MATCHED,false);
    }
    private IngestionResult matchRefund(UUID tx,SePayRequest request){
        String ref=extract(request,REFUND_REF); if(ref==null)return reject(tx,MatchStatus.IGNORED_OUTGOING,"REFUND_REFERENCE_NOT_FOUND",null,null);
        Optional<RefundMatch> match=repository.lockRefund(ref); if(match.isEmpty())return reject(tx,MatchStatus.UNMATCHED,"REFUND_NOT_FOUND",null,null);
        RefundMatch r=match.get(); BigDecimal amount=request.transferAmount().abs();
        if(r.amount().compareTo(amount)!=0)return reject(tx,MatchStatus.AMOUNT_MISMATCH,"REFUND_AMOUNT_MISMATCH",r.paymentId(),r.refundId());
        if(!r.destinationComplete()||!("PENDING".equals(r.status())||"PROCESSING".equals(r.status())))
            return reject(tx,MatchStatus.MANUAL_REVIEW,"REFUND_NOT_MATCHABLE",r.paymentId(),r.refundId());
        repository.completeRefund(tx,r,request,clock.instant()); return new IngestionResult(tx,MatchStatus.MATCHED,false);
    }
    public RefundResponse createRefund(CreateRefundRequest request,UUID actorId){return repository.createRefund(request,actorId);}
    private IngestionResult reject(UUID tx,MatchStatus s,String reason,UUID p,UUID r){repository.rejectTransaction(tx,s,reason,p,r);return new IngestionResult(tx,s,false);}
    private String extract(SePayRequest r,Pattern p){Set<String> refs=new LinkedHashSet<>();for(String v:List.of(nvl(r.code()),nvl(r.content()),nvl(r.description()))){Matcher m=p.matcher(v.toUpperCase());while(m.find())refs.add(m.group());}return refs.size()==1?refs.iterator().next():null;}
    private String paymentReference(UUID id){return "DH"+id.toString().replace("-","").substring(0,10).toUpperCase();}
    private Instant parseTime(String v){return LocalDateTime.parse(v,java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();}
    private Map<String,Object> redacted(SePayRequest r){Map<String,Object> m=new LinkedHashMap<>();m.put("gateway",r.gateway());m.put("code",r.code());m.put("content",r.content());m.put("transferType",r.transferType());m.put("referenceCode",r.referenceCode());return m;}
    private String first(String a,String b){return a!=null&&!a.isBlank()?a:b;} private String nvl(String v){return v==null?"":v;}
}
