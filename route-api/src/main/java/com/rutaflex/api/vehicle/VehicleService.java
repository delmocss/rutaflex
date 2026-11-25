package com.rutaflex.api.vehicle;

import com.rutaflex.api.common.NotFoundException;
import com.rutaflex.api.warehouse.WarehouseService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VehicleService {

    private final VehicleRepository repository;
    private final WarehouseService warehouses;

    VehicleService(VehicleRepository repository, WarehouseService warehouses) {
        this.repository = repository;
        this.warehouses = warehouses;
    }

    public VehicleResponse create(UUID warehouseId, VehicleRequest request) {
        warehouses.requireExists(warehouseId);
        return repository.insert(warehouseId, request);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listByWarehouse(UUID warehouseId) {
        warehouses.requireExists(warehouseId);
        return repository.findByWarehouse(warehouseId);
    }

    @Transactional(readOnly = true)
    public VehicleResponse get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Vehicle", id));
    }

    public VehicleResponse update(UUID id, VehicleRequest request) {
        return repository.update(id, request).orElseThrow(() -> new NotFoundException("Vehicle", id));
    }

    public void delete(UUID id) {
        if (!repository.deleteById(id)) {
            throw new NotFoundException("Vehicle", id);
        }
    }
}
