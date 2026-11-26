package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationCompleted.RouteStop;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationRequested.GeoPoint;
import com.rutaflex.events.OptimizationRequested.Stop;
import com.rutaflex.events.OptimizationRequested.Vehicle;
import com.rutaflex.events.OptimizationRequested.Warehouse;
import com.rutaflex.optimizer.solver.Assumptions;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import com.rutaflex.optimizer.solver.RouteSolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Resuelve el dataset sintético real (80 paradas en Madrid sur) y comprueba la solución con un cálculo independiente. */
class RouteOptimizerTest {

    private static final GeoPoint DEPOT = new GeoPoint(40.1985, -3.6560);
    private static final Pattern CSV_LINE =
            Pattern.compile("^(?:\"([^\"]*)\"|([^,]*)),([\\d.]+),(-?[\\d.]+),(\\d+),(\\d{2}:\\d{2})?,(\\d{2}:\\d{2})?$");

    private static List<Stop> sampleStops;
    private static RouteOptimizer optimizer;

    @BeforeAll
    static void load() throws IOException {
        sampleStops = readSampleStops();
        optimizer = new RouteOptimizer(new RouteSolver(Duration.ofSeconds(20), Duration.ofSeconds(6), 42L),
                Assumptions.DEFAULT);
    }

    @Test
    void solvesTheWholeDayAndBeatsTheUnoptimisedBaseline() {
        OptimizationRequested request = request(sampleStops, fleet());

        OptimizationCompleted result = optimizer.optimize(request);

        // Todas las paradas, exactamente una vez.
        List<UUID> assigned = result.routes().stream().flatMap(r -> r.stops().stream()).map(RouteStop::stopId).toList();
        assertThat(assigned).containsExactlyInAnyOrderElementsOf(sampleStops.stream().map(Stop::id).toList());

        Map<UUID, Stop> stopsById = new HashMap<>();
        sampleStops.forEach(s -> stopsById.put(s.id(), s));
        Map<UUID, Vehicle> vehiclesById = new HashMap<>();
        request.vehicles().forEach(v -> vehiclesById.put(v.id(), v));

        double recomputedKm = 0;
        for (VehicleRoute route : result.routes()) {
            Vehicle van = vehiclesById.get(route.vehicleId());
            // Capacidad.
            int parcels = route.stops().stream().mapToInt(s -> stopsById.get(s.stopId()).parcels()).sum();
            assertThat(parcels).as("parcels of " + van.name()).isLessThanOrEqualTo(van.capacityParcels());
            // Secuencia 1..n y horas crecientes que respetan franja y jornada.
            LocalTime previous = van.shiftStart();
            for (int i = 0; i < route.stops().size(); i++) {
                RouteStop stop = route.stops().get(i);
                Stop data = stopsById.get(stop.stopId());
                assertThat(stop.sequence()).isEqualTo(i + 1);
                assertThat(stop.estimatedArrival()).isAfterOrEqualTo(previous);
                assertThat(stop.estimatedArrival()).isAfterOrEqualTo(van.shiftStart());
                if (data.windowStart() != null) {
                    assertThat(stop.estimatedArrival()).as(data.address()).isBetween(data.windowStart(),
                            data.windowEnd());
                }
                previous = stop.estimatedArrival();
            }
            assertThat(independentReturn(route, stopsById, van)).as("return of " + van.name())
                    .isLessThanOrEqualTo(van.shiftEnd().toSecondOfDay());
            // Km de la ruta recalculados aquí, sin usar el código bajo prueba.
            double km = independentKm(route, stopsById);
            assertThat(route.distanceKm()).as("km of " + van.name()).isCloseTo(km, org.assertj.core.data.Offset.offset(0.011));
            recomputedKm += km;
        }
        assertThat(result.totalKm()).isCloseTo(recomputedKm, org.assertj.core.data.Offset.offset(0.05));

        // El ahorro contra el reparto en orden de llegada es grande (las paradas llegan mezcladas entre tres municipios).
        assertThat(result.baselineKm()).isGreaterThan(result.totalKm());
        double savedPercent = 100 * (result.baselineKm() - result.totalKm()) / result.baselineKm();
        System.out.printf("MEASUREMENT 80 stops, 4 vans: baseline %.1f km -> optimised %.1f km (%.1f%% less) in %d ms%n",
                result.baselineKm(), result.totalKm(), savedPercent, result.computeMillis());
        assertThat(savedPercent).isGreaterThan(30.0);
    }

    @Test
    void aSingleStopIsAThereAndBackTrip() {
        Stop only = new Stop(UUID.randomUUID(), "Unica", new GeoPoint(40.2085, -3.6560), 2, null, null);
        OptimizationCompleted result = quickOptimizer().optimize(request(List.of(only), fleet().subList(0, 1)));

        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().get(0).stops()).extracting(RouteStop::stopId).containsExactly(only.id());
        assertThat(result.totalKm()).isEqualTo(result.baselineKm());
    }

    @Test
    void vansWithoutStopsDoNotAppearInTheResult() {
        List<Stop> few = sampleStops.subList(0, 3);
        OptimizationCompleted result = quickOptimizer().optimize(request(few, fleet()));

        assertThat(result.routes()).isNotEmpty().allSatisfy(r -> assertThat(r.stops()).isNotEmpty());
    }

    @Test
    void resultCarriesTheJobId() {
        OptimizationRequested request = request(sampleStops.subList(0, 5), fleet());

        assertThat(quickOptimizer().optimize(request).jobId()).isEqualTo(request.jobId());
    }

    @Test
    void theSolverKeepsItsIncrementalScoreConsistent() {
        // FULL_ASSERT recalcula la puntuación completa tras cada movimiento y falla si difiere de la incremental:
        // detecta variables sombra mal declaradas o restricciones que leen datos que el solver no vigila.
        RouteOptimizer checked = new RouteOptimizer(
                new RouteSolver(Duration.ofSeconds(5), Duration.ofSeconds(2), 7L, EnvironmentMode.FULL_ASSERT),
                Assumptions.DEFAULT);

        OptimizationCompleted result = checked.optimize(request(sampleStops.subList(0, 15), fleet()));

        assertThat(result.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(15);
    }

    @Test
    void theBaselineNeverBlocksAFeasibleProblem() {
        // Orden 4, 4, 6, 6 con dos furgonetas de 10: cargando en orden la última no cabe, pero 4+6 y 4+6 sí.
        List<Vehicle> vans = List.of(
                new Vehicle(UUID.randomUUID(), "A", 10, LocalTime.of(8, 0), LocalTime.of(15, 0)),
                new Vehicle(UUID.randomUUID(), "B", 10, LocalTime.of(8, 0), LocalTime.of(15, 0)));
        List<Stop> stops = List.of(
                new Stop(UUID.randomUUID(), "uno", new GeoPoint(40.21, -3.65), 4, null, null),
                new Stop(UUID.randomUUID(), "dos", new GeoPoint(40.22, -3.66), 4, null, null),
                new Stop(UUID.randomUUID(), "tres", new GeoPoint(40.23, -3.67), 6, null, null),
                new Stop(UUID.randomUUID(), "cuatro", new GeoPoint(40.24, -3.68), 6, null, null));

        OptimizationCompleted result = quickOptimizer().optimize(request(stops, vans));

        assertThat(result.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(4);
        assertThat(result.baselineKm()).isPositive();
    }

    // ---- entradas inválidas ----

    @Test
    void invalidInputsAreRejectedWithAReasonInsteadOfCrashing() {
        GeoPoint where = new GeoPoint(40.21, -3.66);
        Stop ok = new Stop(UUID.randomUUID(), "ok", where, 1, null, null);
        Vehicle van = new Vehicle(UUID.randomUUID(), "V", 60, LocalTime.of(8, 0), LocalTime.of(15, 0));

        assertRejected(request(List.of(new Stop(UUID.randomUUID(), "neg", where, -5, null, null)), List.of(van)),
                "at least one parcel");
        assertRejected(request(List.of(new Stop(UUID.randomUUID(), "cero", where, 0, null, null)), List.of(van)),
                "at least one parcel");
        assertRejected(request(List.of(new Stop(UUID.randomUUID(), "mitad", where, 1, LocalTime.of(9, 0), null)),
                List.of(van)), "both ends");
        assertRejected(request(List.of(new Stop(UUID.randomUUID(), "reves", where, 1, LocalTime.of(10, 0),
                LocalTime.of(9, 0))), List.of(van)), "ends before it starts");
        assertRejected(request(List.of(ok, ok), List.of(van)), "unique");
        assertRejected(request(List.of(ok), List.of(van, van)), "unique");
        assertRejected(request(List.of(ok), List.of(new Vehicle(UUID.randomUUID(), "cero", 0, LocalTime.of(8, 0),
                LocalTime.of(15, 0)))), "positive capacity");
        assertRejected(request(List.of(ok), List.of(new Vehicle(UUID.randomUUID(), "noche", 60, LocalTime.of(22, 0),
                LocalTime.of(2, 0)))), "ends after it starts");
        assertRejected(request(List.of(new Stop(UUID.randomUUID(), "sin sitio", null, 1, null, null)), List.of(van)),
                "no location");
        assertRejected(new OptimizationRequested(UUID.randomUUID(), LocalDate.of(2026, 12, 14), null, List.of(van),
                List.of(ok)), "warehouse");
    }

    private static void assertRejected(OptimizationRequested request, String reason) {
        assertThatThrownBy(() -> quickOptimizer().optimize(request))
                .isInstanceOf(OptimizationException.class).hasMessageContaining(reason);
    }

    // ---- problemas que no se pueden resolver ----

    @Test
    void moreParcelsThanCapacityIsRejectedBeforeSolving() {
        List<Vehicle> small = List.of(new Vehicle(UUID.randomUUID(), "Pequena", 10, LocalTime.of(8, 0), LocalTime.of(15, 0)));

        assertThatThrownBy(() -> quickOptimizer().optimize(request(sampleStops, small)))
                .isInstanceOf(OptimizationException.class)
                .hasMessageContaining("parcels but the vehicles only carry 10");
    }

    @Test
    void aStopBiggerThanAnyVanIsRejected() {
        Stop huge = new Stop(UUID.randomUUID(), "Enorme", new GeoPoint(40.21, -3.66), 100, null, null);
        List<Vehicle> vans = List.of(
                new Vehicle(UUID.randomUUID(), "A", 60, LocalTime.of(8, 0), LocalTime.of(15, 0)),
                new Vehicle(UUID.randomUUID(), "B", 60, LocalTime.of(8, 0), LocalTime.of(15, 0)));

        assertThatThrownBy(() -> quickOptimizer().optimize(request(List.of(huge), vans)))
                .isInstanceOf(OptimizationException.class)
                .hasMessageContaining("more than any single vehicle");
    }

    @Test
    void noVehiclesOrNoStopsIsRejected() {
        assertThatThrownBy(() -> quickOptimizer().optimize(request(sampleStops, List.of())))
                .isInstanceOf(OptimizationException.class).hasMessageContaining("no vehicles");
        assertThatThrownBy(() -> quickOptimizer().optimize(request(List.of(), fleet())))
                .isInstanceOf(OptimizationException.class).hasMessageContaining("no stops");
    }

    @Test
    void aWindowThatCannotBeMetMakesTheProblemInfeasible() {
        // La parada está a unos 2 km del almacén y la furgoneta sale a las 08:00: no puede llegar a las 08:00:10.
        Stop impossible = new Stop(UUID.randomUUID(), "Imposible", new GeoPoint(40.2185, -3.6560), 1,
                LocalTime.of(8, 0), LocalTime.of(8, 0, 10));

        assertThatThrownBy(() -> quickOptimizer().optimize(request(List.of(impossible), fleet())))
                .isInstanceOf(OptimizationException.class)
                .hasMessageContaining("No feasible plan");
    }

    @Test
    void aShiftTooShortToComeBackMakesTheProblemInfeasible() {
        Stop far = new Stop(UUID.randomUUID(), "Lejos", new GeoPoint(40.40, -3.60), 1, null, null);
        List<Vehicle> brief = List.of(new Vehicle(UUID.randomUUID(), "Corta", 60, LocalTime.of(8, 0), LocalTime.of(8, 10)));

        assertThatThrownBy(() -> quickOptimizer().optimize(request(List.of(far), brief)))
                .isInstanceOf(OptimizationException.class)
                .hasMessageContaining("No feasible plan");
    }

    // ---- ayudas ----

    private static RouteOptimizer quickOptimizer() {
        return new RouteOptimizer(new RouteSolver(Duration.ofSeconds(3), Duration.ofSeconds(1), 1L), Assumptions.DEFAULT);
    }

    private static OptimizationRequested request(List<Stop> stops, List<Vehicle> vehicles) {
        return new OptimizationRequested(UUID.randomUUID(), LocalDate.of(2026, 12, 14),
                new Warehouse(UUID.randomUUID(), "Almacen", DEPOT), vehicles, stops);
    }

    /** Cuatro furgonetas de 60 bultos, como en el dataset de ejemplo: dos de 8 a 15 y dos de 9 a 16. */
    private static List<Vehicle> fleet() {
        List<Vehicle> vans = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int start = i <= 2 ? 8 : 9;
            vans.add(new Vehicle(UUID.randomUUID(), "Furgoneta " + i, 60, LocalTime.of(start, 0),
                    LocalTime.of(start + 7, 0)));
        }
        return vans;
    }

    private static double independentKm(VehicleRoute route, Map<UUID, Stop> stops) {
        double meters = 0;
        double lat = DEPOT.lat();
        double lon = DEPOT.lon();
        for (RouteStop stop : route.stops()) {
            GeoPoint to = stops.get(stop.stopId()).location();
            meters += haversine(lat, lon, to.lat(), to.lon()) * 1.3;
            lat = to.lat();
            lon = to.lon();
        }
        meters += haversine(lat, lon, DEPOT.lat(), DEPOT.lon()) * 1.3;
        return meters / 1000.0;
    }

    /**
     * Hora (segundos) a la que la furgoneta vuelve al almacén, recalculada paso a paso: viaje, espera a que
     * abra la franja, servicio y vuelta. Además comprueba que la hora informada de cada parada cuadra con la cadena.
     */
    private static int independentReturn(VehicleRoute route, Map<UUID, Stop> stops, Vehicle van) {
        double clock = van.shiftStart().toSecondOfDay();
        double lat = DEPOT.lat();
        double lon = DEPOT.lon();
        for (RouteStop stop : route.stops()) {
            Stop data = stops.get(stop.stopId());
            clock += haversine(lat, lon, data.location().lat(), data.location().lon()) * 1.3 / (30.0 / 3.6);
            if (data.windowStart() != null) {
                assertThat(clock).as(data.address() + " must arrive by the window end")
                        .isLessThanOrEqualTo(data.windowEnd().toSecondOfDay() + 1);
                clock = Math.max(clock, data.windowStart().toSecondOfDay());
            }
            assertThat((double) stop.estimatedArrival().toSecondOfDay()).as("reported time of " + data.address())
                    .isBetween(clock - 3, clock + 3);
            clock += 120 + 30 * data.parcels();
            lat = data.location().lat();
            lon = data.location().lon();
        }
        clock += haversine(lat, lon, DEPOT.lat(), DEPOT.lon()) * 1.3 / (30.0 / 3.6);
        return (int) Math.ceil(clock);
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static List<Stop> readSampleStops() throws IOException {
        try (var in = RouteOptimizerTest.class.getResourceAsStream("/madrid-sur-stops.csv")) {
            assertThat(in).isNotNull();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).toList();
            List<Stop> stops = new ArrayList<>();
            for (String line : lines) {
                Matcher m = CSV_LINE.matcher(line);
                assertThat(m.matches()).as(line).isTrue();
                String address = m.group(1) != null ? m.group(1) : m.group(2);
                LocalTime start = m.group(6) == null ? null : LocalTime.parse(m.group(6));
                LocalTime end = m.group(7) == null ? null : LocalTime.parse(m.group(7));
                stops.add(new Stop(UUID.randomUUID(), address,
                        new GeoPoint(Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))),
                        Integer.parseInt(m.group(5)), start, end));
            }
            assertThat(stops).hasSize(80);
            return stops;
        }
    }
}
