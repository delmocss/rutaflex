package com.rutaflex.api.warehouse;

import com.rutaflex.api.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WarehouseService {

    private final WarehouseRepository repository;

    WarehouseService(WarehouseRepository repository) {
        this.repository = repository;
    }

    public WarehouseResponse create(WarehouseRequest request) {
        return repository.insert(request);
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Warehouse", id);
        }
    }

    public WarehouseResponse update(UUID id, WarehouseRequest request) {
        return repository.update(id, request).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    public void delete(UUID id) {
        if (!repository.deleteById(id)) {
            throw new NotFoundException("Warehouse", id);
        }
    }
}
