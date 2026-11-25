package com.rutaflex.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Flyway aplica las migraciones y el propio esquema impide datos incoherentes. */
class DatabaseSchemaTest extends AbstractApiTest {

    @Test
    void flywayCreatesTheSchemaWithPostgis() {
        assertThat(jdbc.queryForObject("SELECT postgis_version()", String.class)).isNotBlank();

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("warehouse", "vehicle", "delivery_stop", "optimization_job");

        // Las coordenadas son geography, no dos columnas de numeros.
        assertThat(jdbc.queryForObject(
                "SELECT udt_name FROM information_schema.columns WHERE table_name = 'delivery_stop' AND column_name = 'location'",
                String.class)).isEqualTo("geography");
    }

    @Test
    void distancesComeOutInMetres() {
        // Valdemoro -> Pinto: unos 6 km en linea recta.
        Double metres = jdbc.queryForObject("""
                SELECT ST_Distance(
                    ST_SetSRID(ST_MakePoint(-3.6742, 40.1908), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(-3.6997, 40.2428), 4326)::geography)
                """, Double.class);
        assertThat(metres).isBetween(5_000.0, 7_000.0);
    }

    @Test
    void vehicleShiftMustEndAfterItStarts() {
        UUID warehouse = insertWarehouse();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO vehicle (warehouse_id, name, capacity_parcels, shift_start, shift_end) "
                        + "VALUES (?, 'V', 10, '15:00', '08:00')", warehouse))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stopTimeWindowIsAllOrNothing() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO delivery_stop (delivery_date, address, location, parcels, window_start)
                VALUES ('2026-12-14', 'Calle 1', ST_SetSRID(ST_MakePoint(-3.7, 40.2), 4326)::geography, 1, '09:00')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFailedJobMustSayWhy() {
        UUID warehouse = insertWarehouse();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO optimization_job (warehouse_id, delivery_date, status) VALUES (?, '2026-12-14', 'FAILED')",
                warehouse)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertWarehouse() {
        return jdbc.queryForObject("""
                INSERT INTO warehouse (name, address, location)
                VALUES ('Almacen', 'Valdemoro', ST_SetSRID(ST_MakePoint(-3.6742, 40.1908), 4326)::geography)
                RETURNING id
                """, UUID.class);
    }
}
