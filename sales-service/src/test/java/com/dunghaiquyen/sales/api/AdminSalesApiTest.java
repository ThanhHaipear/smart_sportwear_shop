package com.dunghaiquyen.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.sales.persistence.SalesAdminRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminSalesApiTest {
    @Test
    void forwardsRevenueGranularityAndDateRange() {
        SalesAdminRepository repository = mock(SalesAdminRepository.class);
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 11, 14);
        Map<String, Object> report = Map.of("granularity", "YEAR", "points", java.util.List.of());
        when(repository.revenueReport("YEAR", from, to)).thenReturn(report);

        AdminSalesApi api = new AdminSalesApi(repository);

        assertThat(api.revenue(AdminSalesApi.RevenueGranularity.YEAR, from, to).data()).isSameAs(report);
        verify(repository).revenueReport("YEAR", from, to);
    }

    @Test
    void forwardsReportPaginationAndFilters() {
        SalesAdminRepository repository = mock(SalesAdminRepository.class);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 14);
        when(repository.customerReport(2, 5, from, to)).thenReturn(Map.of("customers", List.of()));
        when(repository.productReport(3, 4, from, to)).thenReturn(Map.of("bestSelling", List.of()));
        AdminSalesApi api = new AdminSalesApi(repository);

        api.customers(2, 5, from, to);
        api.products(3, 4, from, to);

        verify(repository).customerReport(2, 5, from, to);
        verify(repository).productReport(3, 4, from, to);
    }

    @Test
    void forwardsOrderAndReturnFilters() {
        SalesAdminRepository repository = mock(SalesAdminRepository.class);
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(repository.orderCount("DHQ", "DELIVERED", customerId, null, null, productId)).thenReturn(0L);
        when(repository.orders(20, 0, "DHQ", "DELIVERED", customerId, null, null, productId)).thenReturn(List.of());
        when(repository.returnCount("RECEIVED")).thenReturn(0L);
        when(repository.returns(10, 0, "RECEIVED")).thenReturn(List.of());
        AdminSalesApi api = new AdminSalesApi(repository);

        api.orders(1, 20, "DHQ", "DELIVERED", customerId, null, null, productId);
        api.returns(1, 10, "RECEIVED");

        verify(repository).orderCount("DHQ", "DELIVERED", customerId, null, null, productId);
        verify(repository).orders(20, 0, "DHQ", "DELIVERED", customerId, null, null, productId);
        verify(repository).returnCount("RECEIVED");
        verify(repository).returns(10, 0, "RECEIVED");
    }
}
