package com.dunghaiquyen.catalog.api;

import com.dunghaiquyen.catalog.domain.InventoryReservationService;
import com.dunghaiquyen.catalog.domain.InventoryReservationService.ReservationRequest;
import com.dunghaiquyen.catalog.domain.InventoryReservationService.ReservationResponse;
import com.dunghaiquyen.catalog.persistence.CatalogRepository;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class CatalogApi {
    private final CatalogRepository repository;
    private final InventoryReservationService reservations;

    public CatalogApi(CatalogRepository repository, InventoryReservationService reservations) {
        this.repository = repository;
        this.reservations = reservations;
    }

    @GetMapping("/variants/{id}/sale-snapshot")
    public CatalogRepository.SaleSnapshot saleSnapshot(@PathVariable UUID id) {
        return repository.findSaleSnapshot(id).orElseThrow(() -> new NotFoundException("Variant not found"));
    }

    @GetMapping("/products/{id}/index-document")
    public Map<String, Object> indexDocument(@PathVariable UUID id) {
        return repository.findIndexDocument(id).orElseThrow(() -> new NotFoundException("Product not found"));
    }

    @PostMapping("/inventory/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
        return reservations.reserve(request);
    }

    @PostMapping("/inventory/reservations/{id}/confirm")
    public ReservationResponse confirm(@PathVariable UUID id) { return reservations.confirm(id); }

    @DeleteMapping("/inventory/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id) { reservations.release(id); }
}
