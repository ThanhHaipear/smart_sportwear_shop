package com.dunghaiquyen.sales.catalog;

import com.dunghaiquyen.sales.domain.SalesModels.*;
import java.util.List;
import java.util.UUID;

public interface CatalogClient {
    VariantSnapshot snapshot(UUID variantId);
    Reservation reserve(UUID orderId, List<OrderLineRequest> items);
    void confirm(UUID reservationId);
    void release(UUID reservationId);
}
