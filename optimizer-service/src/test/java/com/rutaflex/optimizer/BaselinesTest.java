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
}
