package com.dunghaiquyen.sales;

import static com.dunghaiquyen.sales.domain.SalesModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dunghaiquyen.sales.catalog.CatalogClient;
import com.dunghaiquyen.sales.config.SalesProperties;
import com.dunghaiquyen.sales.domain.*;
import java.math.BigDecimal;import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;

class SalesDomainServiceTest {
 private SalesRepository repo;private CatalogClient catalog;private SalesDomainService service;private final UUID variant=UUID.randomUUID();
 @BeforeEach void setup(){repo=mock(SalesRepository.class);catalog=mock(CatalogClient.class);SalesProperties p=props();service=new SalesDomainService(repo,catalog,p);
  when(catalog.snapshot(variant)).thenReturn(new VariantSnapshot(variant,UUID.randomUUID(),"SKU","Jersey","M","Blue",new BigDecimal("100000"),true,5));
  when(catalog.reserve(any(),any())).thenReturn(new Reservation(UUID.randomUUID(),Instant.now().plusSeconds(900)));
  when(repo.createOrder(any())).thenAnswer(i->{var o=(OrderAggregate)i.getArgument(0);return new OrderResponse(o.id(),o.orderCode(),"PENDING_CONFIRMATION","PENDING",o.paymentMethod(),o.total(),o.transactionRef(),o.paymentExpiresAt());});}
 @Test void vietQrCheckoutUsesCatalogReservationAndServerPrice(){var r=service.createOrder(new CreateOrderRequest(UUID.randomUUID(),List.of(new OrderLineRequest(variant,2)),PaymentMethod.VIETQR,Map.of("city","HCM"),new BigDecimal("30000"),null));assertThat(r.total()).isEqualByComparingTo("230000");verify(catalog).confirm(any());}
 @Test void failedOrderReleasesReservation(){doThrow(new RuntimeException("db")).when(repo).createOrder(any());assertThatThrownBy(()->service.createOrder(request())).isInstanceOf(RuntimeException.class);verify(catalog).release(any());}
 @Test void failedReservationConfirmationCancelsCommittedOrder(){doThrow(new RuntimeException("catalog")).when(catalog).confirm(any());assertThatThrownBy(()->service.createOrder(request())).isInstanceOf(RuntimeException.class);verify(repo).cancelOrderAfterReservationFailure(any(),any(),eq("CATALOG_RESERVATION_CONFIRM_FAILED"));verify(catalog).release(any());}
 @Test void duplicateWebhookReturnsStoredResult(){UUID id=UUID.randomUUID();when(repo.findIngestion("10")).thenReturn(Optional.of(new IngestionResult(id,MatchStatus.MATCHED,true)));assertThat(service.ingest(webhook(10,"in",new BigDecimal("100000"),"DH1234567890"))).extracting(IngestionResult::alreadyProcessed).isEqualTo(true);verify(repo,never()).insertBankTransaction(any(),any(),any(),any(),any(),any(),any(),any());}
 @Test void latePaymentIsRecordedWithoutMarkingPaid(){UUID tx=UUID.randomUUID(),pay=UUID.randomUUID();when(repo.findIngestion("11")).thenReturn(Optional.empty());when(repo.insertBankTransaction(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(tx);when(repo.lockPayment("DH1234567890")).thenReturn(Optional.of(new PaymentMatch(pay,UUID.randomUUID(),new BigDecimal("100000"),"PENDING","PENDING_CONFIRMATION","PENDING",Instant.now().minusSeconds(1))));assertThat(service.ingest(webhook(11,"in",new BigDecimal("100000"),"DH1234567890")).matchStatus()).isEqualTo(MatchStatus.LATE_PAYMENT);verify(repo,never()).matchPayment(any(),any(),any(),any());}
 @Test void amountMismatchNeverMarksPaymentPaid(){UUID tx=UUID.randomUUID();when(repo.findIngestion("12")).thenReturn(Optional.empty());when(repo.insertBankTransaction(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(tx);when(repo.lockPayment(any())).thenReturn(Optional.of(new PaymentMatch(UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("120000"),"PENDING","CONFIRMED","PENDING",Instant.now().plusSeconds(500))));assertThat(service.ingest(webhook(12,"in",new BigDecimal("100000"),"DH1234567890")).matchStatus()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);}
 @Test void outgoingRefundIsCompletedOnlyWithExactAmountAndDestination(){UUID tx=UUID.randomUUID();when(repo.findIngestion("13")).thenReturn(Optional.empty());when(repo.insertBankTransaction(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(tx);when(repo.lockRefund("RFD1234567890123")).thenReturn(Optional.of(new RefundMatch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("50000"),"PENDING",true)));assertThat(service.ingest(webhook(13,"out",new BigDecimal("50000"),"RFD1234567890123")).matchStatus()).isEqualTo(MatchStatus.MATCHED);verify(repo).completeRefund(eq(tx),any(),any(),any());}
 private CreateOrderRequest request(){return new CreateOrderRequest(UUID.randomUUID(),List.of(new OrderLineRequest(variant,1)),PaymentMethod.VIETQR,Map.of(),BigDecimal.ZERO,null);}
 private SePayRequest webhook(long id,String direction,BigDecimal amount,String ref){return new SePayRequest(id,"VCB","2026-11-10 10:00:00","123456","",ref,"",direction,amount,null,"BANKREF");}
 private SalesProperties props(){return new SalesProperties("internal","http://catalog","catalog",false,1000,new SalesProperties.VietQr("123456",900),new SalesProperties.SePay(true,"test-secret",300));}
}
