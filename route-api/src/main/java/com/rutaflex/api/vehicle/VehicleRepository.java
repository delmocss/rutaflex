package com.rutaflex.api.vehicle;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VehicleRepository {

    private static final String COLUMNS = "id, warehouse_id, name, capacity_parcels, shift_start, shift_end";

    private static final RowMapper<VehicleResponse> MAPPER = (rs, row) -> new VehicleResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("warehouse_id", UUID.class),
            rs.getString("name"),
            rs.getInt("capacity_parcels"),
            rs.getObject("shift_start", LocalTime.class),
            rs.getObject("shift_end", LocalTime.class));

    private final JdbcClient jdbc;

    VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    VehicleResponse insert(UUID warehouseId, VehicleRequest request) {
        return jdbc.sql("""
                        INSERT INTO vehicle (warehouse_id, name, capacity_parcels, shift_start, shift_end)
                        VALUES (:warehouseId, :name, :capacity, :shiftStart, :shiftEnd)
                        RETURNING\s""" + COLUMNS)
                .param("warehouseId", warehouseId)
                .param("name", request.name())
                .param("capacity", request.capacityParcels())
                .param("shiftStart", request.shiftStart())
                .param("shiftEnd", request.shiftEnd())
                .query(MAPPER)
                .single();
    }

    List<VehicleResponse> findByWarehouse(UUID warehouseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vehicle WHERE warehouse_id = :warehouseId ORDER BY name")
                .param("warehouseId", warehouseId)
                .query(MAPPER)
                .list();
    }

    Optional<VehicleResponse> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vehicle WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    Optional<VehicleResponse> update(UUID id, VehicleRequest request) {
        return jdbc.sql("""
                        UPDATE vehicle
                        SET name = :name, capacity_parcels = :capacity, shift_start = :shiftStart, shift_end = :shiftEnd
                        WHERE id = :id
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("name", request.name())
                .param("capacity", request.capacityParcels())
                .param("shiftStart", request.shiftStart())
                .param("shiftEnd", request.shiftEnd())
                .query(MAPPER)
                .optional();
    }

    boolean deleteById(UUID id) {
        return jdbc.sql("DELETE FROM vehicle WHERE id = :id").param("id", id).update() > 0;
    }
}
