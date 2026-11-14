package com.dunghaiquyen.sales.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.sales.persistence.SalesAdminRepository;
import java.time.LocalDate;
import java.util.Map;
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
}
