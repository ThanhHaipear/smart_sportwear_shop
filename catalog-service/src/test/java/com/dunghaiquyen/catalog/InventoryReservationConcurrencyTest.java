package com.dunghaiquyen.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.dunghaiquyen.catalog.domain.InventoryReservationService;
import com.dunghaiquyen.catalog.domain.InventoryReservationService.ReservationLine;
import com.dunghaiquyen.catalog.domain.InventoryReservationService.ReservationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class InventoryReservationConcurrencyTest {
    private static final UUID PRODUCT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VARIANT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    @Autowired InventoryReservationService service;
    @Autowired JdbcClient jdbc;

    @BeforeEach void seed() {
        jdbc.sql("delete from inventory_reservation_items").update();
        jdbc.sql("delete from inventory_reservations").update();
        jdbc.sql("delete from product_variants").update();
        jdbc.sql("delete from products").update();
        jdbc.sql("insert into products(id,name,slug,status,description,version) values(:id,'Shoe','shoe','ACTIVE','',1)")
                .param("id", PRODUCT).update();
        jdbc.sql("insert into product_variants(id,product_id,sku,size_label,color_label,price,stock_quantity,reserved_quantity,status,version,updated_at) values(:id,:p,'SKU-1','42','Black',100,5,0,'ACTIVE',1,current_timestamp)")
                .param("id", VARIANT).param("p", PRODUCT).update();
    }

    @Test void concurrentReservationsNeverOversell() throws Exception {
        int callers = 12;
        var pool = Executors.newFixedThreadPool(callers);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i=0;i<callers;i++) {
            int n=i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.reserve(new ReservationRequest(UUID.randomUUID(), "checkout-"+n, List.of(new ReservationLine(VARIANT,5))));
                    return true;
                } catch (RuntimeException expected) { return false; }
            }));
        }
        start.countDown();
        long successes=0;
        for (Future<Boolean> result:results) if(result.get()) successes++;
        pool.shutdown();
        assertThat(successes).isEqualTo(1);
        Integer reserved=jdbc.sql("select reserved_quantity from product_variants where id=:id").param("id",VARIANT).query(Integer.class).single();
        assertThat(reserved).isEqualTo(5);
    }

    @Test void retryWithSameKeyReturnsSameReservation() {
        var request=new ReservationRequest(UUID.randomUUID(),"same-key",List.of(new ReservationLine(VARIANT,2)));
        var first=service.reserve(request);
        var replay=service.reserve(request);
        assertThat(replay.reservationId()).isEqualTo(first.reservationId());
        assertThat(jdbc.sql("select reserved_quantity from product_variants where id=:id").param("id",VARIANT).query(Integer.class).single()).isEqualTo(2);
    }
}
