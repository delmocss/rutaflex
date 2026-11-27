package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationRequested.GeoPoint;
import com.rutaflex.events.OptimizationRequested.Stop;
import com.rutaflex.events.OptimizationRequested.Vehicle;
import com.rutaflex.events.OptimizationRequested.Warehouse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El dataset sintético de ejemplo (80 paradas en Madrid sur, el mismo CSV que carga la API) con la flota y el
 * almacén de los datos de ejemplo. Los ids de las paradas se generan a partir de su posición en el fichero, no
 * al azar, para que cualquier medición sea idéntica entre ejecuciones.
 */
final class SampleDataset {

    static final GeoPoint DEPOT = new GeoPoint(40.1985, -3.6560);

    private static final Pattern CSV_LINE =
            Pattern.compile("^(?:\"([^\"]*)\"|([^,]*)),([\\d.]+),(-?[\\d.]+),(\\d+),(\\d{2}:\\d{2})?,(\\d{2}:\\d{2})?$");

    private SampleDataset() {
    }

    /** Las 80 paradas, en el orden del fichero. */
    static List<Stop> stops() {
        try (var in = SampleDataset.class.getResourceAsStream("/madrid-sur-stops.csv")) {
            assertThat(in).isNotNull();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).toList();
            List<Stop> stops = new ArrayList<>();
            int index = 0;
            for (String line : lines) {
                Matcher m = CSV_LINE.matcher(line);
                assertThat(m.matches()).as(line).isTrue();
                String address = m.group(1) != null ? m.group(1) : m.group(2);
                LocalTime start = m.group(6) == null ? null : LocalTime.parse(m.group(6));
                LocalTime end = m.group(7) == null ? null : LocalTime.parse(m.group(7));
                stops.add(new Stop(new UUID(1L, index++), address,
                        new GeoPoint(Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))),
                        Integer.parseInt(m.group(5)), start, end));
            }
            assertThat(stops).hasSize(80);
            return stops;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cuatro furgonetas de 60 bultos: dos de 8 a 15 y dos de 9 a 16, como en los datos de ejemplo. */
    static List<Vehicle> fleet() {
        List<Vehicle> vans = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int start = i <= 2 ? 8 : 9;
            vans.add(new Vehicle(new UUID(2L, i), "Furgoneta " + i, 60, LocalTime.of(start, 0),
                    LocalTime.of(start + 7, 0)));
        }
        return vans;
    }

    static OptimizationRequested request(List<Stop> stops, List<Vehicle> vehicles) {
        return new OptimizationRequested(new UUID(3L, 1), LocalDate.of(2026, 12, 14),
                new Warehouse(new UUID(4L, 1), "Almacen", DEPOT), vehicles, stops);
    }

    static OptimizationRequested request() {
        return request(stops(), fleet());
    }
}
