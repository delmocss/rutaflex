package com.rutaflex.api.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.api.AbstractApiTest;
import com.rutaflex.api.stop.ImportResult;
import com.rutaflex.api.stop.StopService;
import java.io.InputStream;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

/** El dataset de ejemplo y su cargador, con los limites por defecto (no los reducidos de otros tests). */
@TestPropertySource(properties = {
        "rutaflex.sample-data.enabled=true",
        "rutaflex.sample-data.delivery-date=2026-12-14"})
class SampleDataTest extends AbstractApiTest {

    static final LocalDate DAY = LocalDate.of(2026, 12, 14);

    @Autowired
    SampleDataSeeder seeder;

    @Autowired
    StopService stops;

    @Test
    void theSampleCsvImportsCleanlyAndLooksLikeMadridSur() throws Exception {
        try (InputStream csv = new ClassPathResource(SampleDataSeeder.STOPS_RESOURCE).getInputStream()) {
            ImportResult result = stops.importCsv(DAY, csv, false);
            assertThat(result.imported()).isEqualTo(80);
        }

        // Todo dentro de Madrid sur: a menos de 20 km del almacen de ejemplo (Valdemoro).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM delivery_stop
                WHERE ST_DWithin(location, ST_SetSRID(ST_MakePoint(-3.6560, 40.1985), 4326)::geography, 20000)""",
                Integer.class)).isEqualTo(80);

        // Reparto en los tres municipios, no concentrado en uno.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop WHERE address LIKE '%, Valdemoro'", Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop WHERE address LIKE '%, Pinto'", Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop WHERE address LIKE '%, Getafe'", Integer.class)).isEqualTo(28);

        // Entre una cuarta parte y la mitad con franja horaria (el resto, flexibles).
        Integer withWindow = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_stop WHERE window_start IS NOT NULL", Integer.class);
        assertThat(withWindow).isBetween(20, 40);

        // Los bultos caben en la flota de ejemplo (4 x 60 = 240) con holgura limitada.
        Integer parcels = jdbc.queryForObject("SELECT sum(parcels) FROM delivery_stop", Integer.class);
        assertThat(parcels).isBetween(150, 240);
    }

    @Test
    void seederCreatesTheFleetAndTheStopsAndIsIdempotent() throws Exception {
        seeder.run(null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_stop WHERE delivery_date = '2026-12-14'", Integer.class)).isEqualTo(80);

        seeder.run(null); // segunda vez: no duplica nada

        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isEqualTo(80);
    }
}
