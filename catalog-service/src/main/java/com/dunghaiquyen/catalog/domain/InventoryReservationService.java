package com.dunghaiquyen.catalog.domain;

import com.dunghaiquyen.catalog.api.ConflictException;
import com.dunghaiquyen.catalog.persistence.CatalogRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReservationService {
    private final JdbcClient jdbc;
    private final CatalogRepository catalog;

    public InventoryReservationService(JdbcClient jdbc, CatalogRepository catalog) { this.jdbc = jdbc; this.catalog = catalog; }

    public record ReservationLine(@NotNull UUID variantId, @Positive int quantity) {}
    public record ReservationRequest(@NotNull UUID orderId, @NotBlank String idempotencyKey,
                                     @NotEmpty List<@Valid ReservationLine> items) {}
    public record ReservationResponse(UUID reservationId, UUID orderId, String status, Instant expiresAt,
                                      List<ReservationLine> items) {}

    @Transactional
    public ReservationResponse reserve(ReservationRequest request) {
        var existing = findByKey(request.idempotencyKey());
        if (existing != null) return existing;
        List<ReservationLine> items = normalize(request.items());
        List<UUID> ids = items.stream().map(ReservationLine::variantId).toList();
        Map<UUID, CatalogRepository.LockedVariant> locked = catalog.lockVariants(ids).stream()
                .collect(Collectors.toMap(CatalogRepository.LockedVariant::id, Function.identity()));
        // A concurrent replay may have passed the first lookup before the original committed.
        // The deterministic variant lock serializes both callers; re-check now to preserve idempotency.
        existing = findByKey(request.idempotencyKey());
        if (existing != null) return existing;
        for (ReservationLine item : items) {
            var variant = locked.get(item.variantId());
            if (variant == null) throw new ConflictException("Variant not found: " + item.variantId());
            if (!"ACTIVE".equals(variant.status()) || !"ACTIVE".equals(variant.productStatus()))
                throw new ConflictException("Variant is not saleable: " + item.variantId());
            if (variant.stock() - variant.reserved() < item.quantity())
                throw new ConflictException("Insufficient stock: " + item.variantId());
        }
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        jdbc.sql("insert into inventory_reservations(id,order_ref,idempotency_key,status,expires_at) values(:id,:order,:key,'ACTIVE',:expires)")
                .param("id", reservationId).param("order", request.orderId()).param("key", request.idempotencyKey())
                .param("expires", expiresAt).update();
        for (ReservationLine item : items) {
            jdbc.sql("insert into inventory_reservation_items(reservation_id,variant_id,quantity) values(:r,:v,:q)")
                    .param("r", reservationId).param("v", item.variantId()).param("q", item.quantity()).update();
            catalog.addReserved(item.variantId(), item.quantity());
        }
        return new ReservationResponse(reservationId, request.orderId(), "ACTIVE", expiresAt, items);
    }

    @Transactional
    public ReservationResponse confirm(UUID id) { return transition(id, "CONFIRMED", true); }
    @Transactional
    public void release(UUID id) { transition(id, "RELEASED", false); }

    private ReservationResponse transition(UUID id, String target, boolean confirm) {
        var row = jdbc.sql("select id,order_ref,status,expires_at from inventory_reservations where id=:id for update")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ConflictException("Reservation not found"));
        List<ReservationLine> items = reservationItems(id);
        String status = row.get("status").toString();
        if (target.equals(status)) return response(row, items);
        if (!"ACTIVE".equals(status)) throw new ConflictException("Reservation is already " + status);
        catalog.lockVariants(items.stream().map(ReservationLine::variantId).sorted().toList());
        items.forEach(item -> { if (confirm) catalog.confirm(item.variantId(), item.quantity()); else catalog.release(item.variantId(), item.quantity()); });
        jdbc.sql("update inventory_reservations set status=:status,updated_at=current_timestamp where id=:id")
                .param("status", target).param("id", id).update();
        return new ReservationResponse(id, (UUID) row.get("order_ref"), target, toInstant(row.get("expires_at")), items);
    }

    private ReservationResponse findByKey(String key) {
        return jdbc.sql("select id,order_ref,status,expires_at from inventory_reservations where idempotency_key=:key")
                .param("key", key).query().listOfRows().stream().findFirst()
                .map(row -> response(row, reservationItems((UUID) row.get("id")))).orElse(null);
    }
    private ReservationResponse response(Map<String,Object> row, List<ReservationLine> items) {
        return new ReservationResponse((UUID) row.get("id"), (UUID) row.get("order_ref"), row.get("status").toString(),
                toInstant(row.get("expires_at")), items);
    }
    private Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }
    private List<ReservationLine> reservationItems(UUID id) {
        return jdbc.sql("select variant_id,quantity from inventory_reservation_items where reservation_id=:id order by variant_id")
                .param("id", id).query((rs,n) -> new ReservationLine(rs.getObject(1, UUID.class), rs.getInt(2))).list();
    }
    private List<ReservationLine> normalize(List<ReservationLine> items) {
        return items.stream().collect(Collectors.groupingBy(ReservationLine::variantId, Collectors.summingInt(ReservationLine::quantity)))
                .entrySet().stream().map(e -> new ReservationLine(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ReservationLine::variantId)).toList();
    }
}
