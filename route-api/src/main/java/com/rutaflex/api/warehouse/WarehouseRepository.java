package com.rutaflex.api.warehouse;

import com.rutaflex.api.common.GeoPoint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL explicito: la columna es geography y se lee/escribe con funciones de PostGIS. */
@Repository
class WarehouseRepository {

    private static final String COLUMNS =
            "id, name, address, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, created_at";

    private static final RowMapper<WarehouseResponse> MAPPER = (rs, row) -> new WarehouseResponse(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("address"),
            new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    WarehouseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    WarehouseResponse insert(WarehouseRequest request) {
        return jdbc.sql("""
                        INSERT INTO warehouse (name, address, location)
                        VALUES (:name, :address, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
                        RETURNING\s""" + COLUMNS)
                .param("name", request.name())
                .param("address", request.address())
                .param("lon", request.location().lon())
                .param("lat", request.location().lat())
                .query(MAPPER)
                .single();
    }

    List<WarehouseResponse> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM warehouse ORDER BY name, created_at").query(MAPPER).list();
    }

    Optional<WarehouseResponse> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM warehouse WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    boolean existsById(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM warehouse WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    Optional<WarehouseResponse> update(UUID id, WarehouseRequest request) {
        return jdbc.sql("""
                        UPDATE warehouse
                        SET name = :name, address = :address,
                            location = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography
                        WHERE id = :id
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("name", request.name())
                .param("address", request.address())
                .param("lon", request.location().lon())
                .param("lat", request.location().lat())
                .query(MAPPER)
                .optional();
    }

    boolean deleteById(UUID id) {
        return jdbc.sql("DELETE FROM warehouse WHERE id = :id").param("id", id).update() > 0;
    }
}
