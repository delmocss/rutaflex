package com.rutaflex.api.vehicle;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Las furgonetas se crean y listan dentro de su almacen; despues se acceden por su propio id. */
@RestController
class VehicleController {

    private final VehicleService service;

    VehicleController(VehicleService service) {
        this.service = service;
    }

    @PostMapping("/api/warehouses/{warehouseId}/vehicles")
    ResponseEntity<VehicleResponse> create(@PathVariable UUID warehouseId, @Valid @RequestBody VehicleRequest request) {
        VehicleResponse created = service.create(warehouseId, request);
        return ResponseEntity.created(URI.create("/api/vehicles/" + created.id())).body(created);
    }

    @GetMapping("/api/warehouses/{warehouseId}/vehicles")
    List<VehicleResponse> listByWarehouse(@PathVariable UUID warehouseId) {
        return service.listByWarehouse(warehouseId);
    }

    @GetMapping("/api/vehicles/{id}")
    VehicleResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/api/vehicles/{id}")
    VehicleResponse update(@PathVariable UUID id, @Valid @RequestBody VehicleRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/vehicles/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
