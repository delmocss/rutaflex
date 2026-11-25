package com.rutaflex.api.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.api.AbstractApiTest;
import com.rutaflex.api.stop.ImportResult;
import com.rutaflex.api.stop.StopImportService;
import com.rutaflex.api.stop.StopResponse;
import com.rutaflex.api.stop.StopService;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
    StopImportService stopImport;

    @Autowired
    StopService stops;

    @Test
    void theSampleCsvImportsCleanlyAndLooksLikeMadridSur() throws Exception {
        try (InputStream csv = new ClassPathResource(SampleDataSeeder.STOPS_RESOURCE).getInputStream()) {
            ImportResult result = stopImport.importCsv(DAY, csv, false);
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
    void stopsComeBackInTheOrderOfTheFile() throws Exception {
        try (InputStream csv = new ClassPathResource(SampleDataSeeder.STOPS_RESOURCE).getInputStream()) {
            stopImport.importCsv(DAY, csv, false);
        }

        List<String> fileOrder = new java.util.ArrayList<>();
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(SampleDataSeeder.STOPS_RESOURCE).getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (CSVRecord record : parser) {
                fileOrder.add(record.get("address") + "|" + record.get("lat") + "|" + record.get("lon"));
            }
        }

        List<String> listed = stops.listByDate(DAY).stream()
                .map(StopResponse::address)
                .toList();
        List<String> expectedAddresses = fileOrder.stream().map(row -> row.split("\\|")[0]).toList();

        // Es la linea base de "sin optimizar": el orden de llegada debe ser el del fichero, siempre.
        assertThat(listed).containsExactlyElementsOf(expectedAddresses);
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

    @Test
    void seederNeverTouchesExistingData() throws Exception {
        // Paradas cargadas a mano para ese mismo dia, sin ningun almacen: no se borran ni se mezclan.
        jdbc.update("""
                INSERT INTO delivery_stop (delivery_date, address, location, parcels)
                VALUES ('2026-12-14', 'Parada real', ST_SetSRID(ST_MakePoint(-3.7, 40.2), 4326)::geography, 1)""");

        seeder.run(null);

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop", String.class)).containsExactly("Parada real");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse", Integer.class)).isZero();
    }
}
