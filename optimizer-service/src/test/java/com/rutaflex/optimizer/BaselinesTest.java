package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RoutePlan;
import com.rutaflex.optimizer.solver.RouteVehicle;
import com.rutaflex.optimizer.solver.Visit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Las referencias con números calculables a mano: puntos sobre un meridiano a 0,01 grados (1.112 m) entre sí,
 * sin factor de rodeo y a 36 km/h (10 m/s). Cada bulto tarda 60 s en entregarse.
 */
class BaselinesTest {

    private static final Assumptions SIMPLE = new Assumptions(1.0, 36.0, 0, 60);
    private static final int EIGHT = 8 * 3600;

    private static RouteVehicle van(int capacity, int shiftEnd) {
        return new RouteVehicle(UUID.randomUUID(), "van", capacity, EIGHT, shiftEnd, 40.0, -3.7, SIMPLE);
    }

    private static Visit stop(int steps, int parcels, Integer windowStart, Integer windowEnd) {
        return new Visit(new UUID(0, steps * 100L + parcels), "stop " + steps, 40.0 + 0.01 * steps, -3.7, parcels,
                windowStart, windowEnd, SIMPLE);
    }

    private static RoutePlan plan(List<RouteVehicle> vans, Visit... visits) {
        return new RoutePlan(vans, new ArrayList<>(List.of(visits)));
    }

    @Test
    void arrivalOrderFollowsTheOrderTheStopsCame() {
        RoutePlan problem = plan(List.of(van(10, 20 * 3600)), stop(3, 1, null, null), stop(1, 1, null, null),
                stop(2, 1, null, null));

        // almacén→3 (3) + 3→1 (2) + 1→2 (1) + 2→almacén (2) = 8 pasos de 1.112 m
        assertThat(Baselines.arrivalOrder(problem).km()).isEqualTo(8 * 1112 / 1000.0);
    }

    @Test
    void sweepWithNearestNeighbourOrdersByProximityAndBeatsTheArrivalOrder() {
        RoutePlan problem = plan(List.of(van(10, 20 * 3600)), stop(3, 1, null, null), stop(1, 1, null, null),
                stop(2, 1, null, null));

        // ir y volver por el mismo eje: 1 + 1 + 1 + 3 = 6 pasos
        assertThat(Baselines.sweepNearest(problem).km()).isEqualTo(6 * 1112 / 1000.0);
    }

    @Test
    void bothAreDeterministic() {
        List<Visit> visits = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            visits.add(stop(i, 1 + i % 3, null, null));
        }
        RoutePlan problem = new RoutePlan(List.of(van(20, 20 * 3600), van(20, 20 * 3600)), visits);

        assertThat(Baselines.arrivalOrder(problem)).isEqualTo(Baselines.arrivalOrder(problem));
        assertThat(Baselines.sweepNearest(problem)).isEqualTo(Baselines.sweepNearest(problem));
    }

    @Test
    void theSweepGivesEachVanASectorAndRespectsCapacityWhenItCan() {
        // Dos furgonetas de 5 bultos; el barrido las llena por turnos sin pasarse.
        RoutePlan problem = plan(List.of(van(5, 20 * 3600), van(5, 20 * 3600)), stop(1, 3, null, null),
                stop(2, 2, null, null), stop(3, 3, null, null), stop(4, 2, null, null));

        Baselines.Result result = Baselines.sweepNearest(problem);

        assertThat(result.km()).isPositive();
        assertThat(result.shiftOverruns()).isZero();
    }

    @Test
    void ifNothingFitsTheOverflowGoesToTheEmptiestVanInsteadOfFailing() {
        RoutePlan problem = plan(List.of(van(2, 20 * 3600)), stop(1, 2, null, null), stop(2, 2, null, null));

        assertThat(Baselines.arrivalOrder(problem).km()).isPositive();
        assertThat(Baselines.sweepNearest(problem).km()).isPositive();
    }

    @Test
    void itCountsTheStopsThatWouldArriveAfterTheirWindowCloses() {
        // Llega a las 08:01:51 y la franja cerraba a las 08:01:00: tarde. La otra, sin franja, no cuenta.
        RoutePlan problem = plan(List.of(van(10, 20 * 3600)), stop(1, 1, EIGHT, EIGHT + 60),
                stop(2, 1, null, null));

        assertThat(Baselines.arrivalOrder(problem).lateStops()).isEqualTo(1);
        assertThat(Baselines.sweepNearest(problem).lateStops()).isEqualTo(1);
    }

    @Test
    void waitingForAWindowToOpenIsNotLateness() {
        RoutePlan problem = plan(List.of(van(10, 20 * 3600)), stop(1, 1, 10 * 3600, 12 * 3600));

        assertThat(Baselines.arrivalOrder(problem).lateStops()).isZero();
    }

    @Test
    void itCountsTheVansThatWouldNotBeBackBeforeTheEndOfTheirShift() {
        RoutePlan problem = plan(List.of(van(10, EIGHT + 200)), stop(1, 1, null, null));

        // vuelve a las 08:04:42 (282 s despues de salir) y la jornada acababa 200 s despues de salir
        assertThat(Baselines.arrivalOrder(problem).shiftOverruns()).isEqualTo(1);
    }

    @Test
    void measuringDoesNotTouchTheProblem() {
        Visit a = stop(1, 1, null, null);
        RoutePlan problem = plan(List.of(van(10, 20 * 3600)), a, stop(2, 1, null, null));

        Baselines.arrivalOrder(problem);
        Baselines.sweepNearest(problem);

        assertThat(a.getVehicle()).isNull();
        assertThat(a.getArrivalTime()).isNull();
        assertThat(problem.getVehicles().get(0).getVisits()).isEmpty();
    }

    // ---- el barrido con paradas en distintas direcciones (el angulo importa de verdad) ----

    /** Parada a {@code dLat}/{@code dLon} grados del almacén, que está en (40,0, -3,7). */
    private static Visit at(String name, double dLat, double dLon, int parcels) {
        return new Visit(UUID.nameUUIDFromBytes(name.getBytes()), name, 40.0 + dLat, -3.7 + dLon, parcels, null, null,
                SIMPLE);
    }

    private static List<String> names(List<Visit> route) {
        return route.stream().map(Visit::getAddress).toList();
    }

    /** Qué paradas lleva cada furgoneta (el orden dentro de la ruta lo decide el vecino más cercano, no el barrido). */
    private static List<java.util.Set<String>> sectors(Baselines.Result result) {
        return result.routes().stream().map(route -> (java.util.Set<String>) new java.util.TreeSet<>(names(route)))
                .toList();
    }

    @Test
    void theSweepGivesEachVanAContiguousSectorStartingFromTheSouth() {
        RoutePlan problem = new RoutePlan(List.of(van(6, 20 * 3600), van(6, 20 * 3600)),
                new ArrayList<>(List.of(at("N", 0.01, 0, 3), at("E", 0, 0.01, 3), at("S", -0.01, 0, 3),
                        at("W", 0, -0.01, 3))));

        Baselines.Result result = Baselines.sweepNearest(problem);

        // El corte está en el sur, y una parada exactamente al sur cae en el corte: cuenta como la primera.
        // Orden del barrido: S, O, N, E. La primera furgoneta se llena con S y O; la segunda, con N y E.
        assertThat(sectors(result)).containsExactly(java.util.Set.of("S", "W"), java.util.Set.of("N", "E"));
        assertThat(result.overloadedVans()).isZero();
    }

    @Test
    void twoStopsOnEitherSideOfTheCutEndUpInDifferentVansAndRotatingTheCutChangesIt() {
        RoutePlan problem = new RoutePlan(List.of(van(6, 20 * 3600), van(6, 20 * 3600)),
                new ArrayList<>(List.of(at("SW", -0.01, -0.001, 3), at("N", 0.01, 0, 3),
                        at("SE", -0.01, 0.001, 3))));

        // Corte en el sur: SO es la primera y SE la última, aunque están juntas: acaban en furgonetas distintas.
        assertThat(sectors(Baselines.sweepNearest(problem)))
                .containsExactly(java.util.Set.of("SW", "N"), java.util.Set.of("SE"));
        // Cortando por el norte, SE y SO quedan seguidas en el orden y, con ellas, N: otro reparto.
        assertThat(sectors(Baselines.sweepNearest(problem, 0)))
                .containsExactly(java.util.Set.of("N", "SE"), java.util.Set.of("SW"));
    }

    @Test
    void theSweepRangeTriesEveryCutAndTheDefaultCutIsInsideIt() {
        RoutePlan problem = new RoutePlan(List.of(van(6, 20 * 3600), van(6, 20 * 3600)),
                new ArrayList<>(List.of(at("N", 0.01, 0, 3), at("E", 0, 0.01, 3), at("S", -0.01, 0, 3),
                        at("W", 0, -0.01, 3))));

        var range = Baselines.sweepRange(problem, 72);

        assertThat(range.getCount()).isEqualTo(72);
        assertThat(range.getMin()).isLessThanOrEqualTo(Baselines.sweepNearest(problem).km());
        assertThat(range.getMax()).isGreaterThanOrEqualTo(Baselines.sweepNearest(problem).km());
    }

    @Test
    void whenNothingFitsTheSweepSaysSoInsteadOfSilentlyOverloadingAVan() {
        RoutePlan roomy = plan(List.of(van(10, 20 * 3600)), stop(1, 2, null, null), stop(2, 2, null, null));
        RoutePlan tight = plan(List.of(van(3, 20 * 3600)), stop(1, 2, null, null), stop(2, 2, null, null));

        assertThat(Baselines.sweepNearest(roomy).overloadedVans()).isZero();
        assertThat(Baselines.arrivalOrder(roomy).overloadedVans()).isZero();
        assertThat(Baselines.sweepNearest(tight).overloadedVans()).isEqualTo(1);
        assertThat(Baselines.arrivalOrder(tight).overloadedVans()).isEqualTo(1);
    }

    @Test
    void everyStopIsAssignedExactlyOnce() {
        RoutePlan problem = new RoutePlan(List.of(van(5, 20 * 3600), van(5, 20 * 3600)),
                new ArrayList<>(List.of(at("a", 0.01, 0.01, 3), at("b", -0.01, 0.01, 3), at("c", -0.01, -0.01, 3),
                        at("d", 0.01, -0.01, 3))));

        for (Baselines.Result result : List.of(Baselines.arrivalOrder(problem), Baselines.sweepNearest(problem))) {
            assertThat(result.routes().stream().flatMap(List::stream).map(Visit::getAddress))
                    .containsExactlyInAnyOrder("a", "b", "c", "d");
        }
    }

    @Test
    void aVanThatFillsUpHandsTheRestOfItsSectorToTheNextVanInsteadOfLookingForLooseGaps() {
        // Orden del barrido: W (4 bultos), N (4), E (2). Con capacidad 6: W llena casi la primera; N ya no cabe y
        // abre la segunda; E sigue a N (mismo sector) aunque aún quepa en la primera.
        RoutePlan problem = new RoutePlan(List.of(van(6, 20 * 3600), van(6, 20 * 3600)),
                new ArrayList<>(List.of(at("W", 0, -0.01, 4), at("N", 0.01, 0, 4), at("E", 0, 0.01, 2))));

        assertThat(sectors(Baselines.sweepNearest(problem)))
                .containsExactly(java.util.Set.of("W"), java.util.Set.of("N", "E"));
    }
}
