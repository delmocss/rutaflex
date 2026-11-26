package com.rutaflex.optimizer.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cada restricción se prueba aislada con números que se calculan a mano. Los puntos están sobre el mismo
 * meridiano, a 0,01 grados de latitud (1.112 m) uno del siguiente, sin factor de rodeo y a 36 km/h
 * (10 m/s): cada tramo de 1.112 m tarda 111 s. Cada bulto tarda 60 s en entregarse.
 */
class RouteConstraintsTest {

    private static final Assumptions SIMPLE = new Assumptions(1.0, 36.0, 0, 60);
    private static final int EIGHT_AM = 8 * 3600;
    private static final double DEPOT_LAT = 40.00;
    private static final double LON = -3.70;

    private final ConstraintVerifier<RouteConstraints, RoutePlan> verifier =
            ConstraintVerifier.build(new RouteConstraints(), RoutePlan.class, RouteVehicle.class, Visit.class);

    private static RouteVehicle van(int capacity, int shiftEnd) {
        return new RouteVehicle(UUID.randomUUID(), "van", capacity, EIGHT_AM, shiftEnd, DEPOT_LAT, LON, SIMPLE);
    }

    /** Parada a {@code steps} pasos de 0,01 grados al norte del almacén. */
    private static Visit stop(int steps, int parcels, Integer windowStart, Integer windowEnd) {
        return new Visit(UUID.randomUUID(), "stop " + steps, DEPOT_LAT + 0.01 * steps, LON, parcels, windowStart,
                windowEnd, SIMPLE);
    }

    /** Reparte las paradas en la furgoneta como lo haría el solver: rellena las variables sombra en orden. */
    private static void route(RouteVehicle vehicle, Visit... visits) {
        vehicle.setVisits(List.of(visits));
        for (int i = 0; i < visits.length; i++) {
            visits[i].setVehicle(vehicle);
            visits[i].setPrevious(i == 0 ? null : visits[i - 1]);
            visits[i].setNext(i == visits.length - 1 ? null : visits[i + 1]);
            visits[i].updateArrivalTime();
        }
    }

    // ---- tiempos de llegada ----

    @Test
    void arrivalTimesAccumulateTravelAndService() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, null, null);
        Visit b = stop(2, 1, null, null);
        route(van, a, b);

        assertThat(a.getArrivalTime()).isEqualTo(EIGHT_AM + 111);
        assertThat(b.getArrivalTime()).isEqualTo(EIGHT_AM + 111 + 60 + 111);
        assertThat(van.returnTime()).isEqualTo(b.departureTime() + 222);
    }

    @Test
    void arrivingBeforeTheWindowOpensMeansWaiting() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, 10 * 3600, 12 * 3600);
        Visit b = stop(2, 1, null, null);
        route(van, a, b);

        assertThat(a.getArrivalTime()).isEqualTo(EIGHT_AM + 111);
        assertThat(a.serviceStart()).isEqualTo(10 * 3600);
        assertThat(b.getArrivalTime()).isEqualTo(10 * 3600 + 60 + 111);
    }

    // ---- capacidad ----

    @Test
    void capacityIsPenalisedByTheParcelsOverTheLimit() {
        RouteVehicle van = van(5, 20 * 3600);
        Visit a = stop(1, 3, null, null);
        Visit b = stop(2, 4, null, null);
        route(van, a, b);

        verifier.verifyThat(RouteConstraints::vehicleCapacity).given(van, a, b).penalizesBy(2);
    }

    @Test
    void oneParcelOverTheCapacityIsAlreadyAViolation() {
        RouteVehicle van = van(5, 20 * 3600);
        Visit a = stop(1, 3, null, null);
        Visit b = stop(2, 3, null, null);
        route(van, a, b);

        verifier.verifyThat(RouteConstraints::vehicleCapacity).given(van, a, b).penalizesBy(1);
    }

    @Test
    void loadingExactlyTheCapacityIsAllowed() {
        RouteVehicle van = van(5, 20 * 3600);
        Visit a = stop(1, 3, null, null);
        Visit b = stop(2, 2, null, null);
        route(van, a, b);

        verifier.verifyThat(RouteConstraints::vehicleCapacity).given(van, a, b).penalizesBy(0);
    }

    @Test
    void eachVehicleIsCheckedOnItsOwn() {
        RouteVehicle full = van(5, 20 * 3600);
        RouteVehicle other = van(5, 20 * 3600);
        Visit a = stop(1, 5, null, null);
        Visit b = stop(2, 5, null, null);
        route(full, a);
        route(other, b);

        verifier.verifyThat(RouteConstraints::vehicleCapacity).given(full, other, a, b).penalizesBy(0);
    }

    // ---- franjas horarias ----

    @Test
    void arrivingAfterTheWindowClosesIsPenalisedInWholeMinutes() {
        RouteVehicle van = van(10, 20 * 3600);
        // Llega a las 08:01:51 y la franja cierra a las 08:01:40: 11 s tarde = 1 minuto.
        Visit a = stop(1, 1, EIGHT_AM, EIGHT_AM + 100);
        route(van, a);

        verifier.verifyThat(RouteConstraints::timeWindow).given(van, a).penalizesBy(1);
    }

    @Test
    void arrivingEarlyOrOnTimeIsNotPenalised() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit early = stop(1, 1, 10 * 3600, 12 * 3600);
        Visit onTime = stop(2, 1, EIGHT_AM, 12 * 3600);
        route(van, early, onTime);

        verifier.verifyThat(RouteConstraints::timeWindow).given(van, early, onTime).penalizesBy(0);
    }

    @Test
    void waitingForAWindowCanMakeLaterStopsLate() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit first = stop(1, 1, 10 * 3600, 12 * 3600);
        // Esperar a que abra la franja del primero (10:00) retrasa a los demás: el tercero llega a las 10:05:42
        // y su franja cerraba a las 10:00 (342 s tarde = 6 minutos).
        Visit second = stop(2, 1, null, null);
        Visit third = new Visit(UUID.randomUUID(), "third", DEPOT_LAT + 0.03, LON, 1, EIGHT_AM, 10 * 3600, SIMPLE);
        route(van, first, second, third);

        verifier.verifyThat(RouteConstraints::timeWindow).given(van, first, second, third).penalizesBy(6);
    }

    @Test
    void stopsWithoutWindowNeverBreakTheConstraint() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, null, null);
        route(van, a);

        verifier.verifyThat(RouteConstraints::timeWindow).given(van, a).penalizesBy(0);
    }

    // ---- fin de jornada ----

    @Test
    void returningAfterTheShiftEndsIsPenalised() {
        // Sale a las 08:00, llega a las 08:01:51, entrega 60 s y vuelve 111 s después: 08:04:42 (282 s tras las 08:00).
        RouteVehicle van = van(10, EIGHT_AM + 200);
        Visit a = stop(1, 1, null, null);
        route(van, a);

        assertThat(van.returnTime()).isEqualTo(EIGHT_AM + 282);
        // 82 s tarde = 2 minutos.
        verifier.verifyThat(RouteConstraints::shiftEnd).given(van, a).penalizesBy(2);
    }

    @Test
    void returningWithinTheShiftIsFine() {
        RouteVehicle van = van(10, EIGHT_AM + 282);
        Visit a = stop(1, 1, null, null);
        route(van, a);

        verifier.verifyThat(RouteConstraints::shiftEnd).given(van, a).penalizesBy(0);
    }

    @Test
    void onlyTheLastStopDecidesWhenTheVanIsBack() {
        RouteVehicle van = van(10, EIGHT_AM + 100_000);
        Visit a = stop(1, 1, null, null);
        Visit b = stop(2, 1, null, null);
        route(van, a, b);

        verifier.verifyThat(RouteConstraints::shiftEnd).given(van, a, b).penalizesBy(0);
    }

    // ---- distancia ----

    @Test
    void aSingleStopCostsTheTripThereAndBack() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, null, null);
        route(van, a);

        verifier.verifyThat(RouteConstraints::totalDistance).given(van, a).penalizesBy(2 * 1112);
    }

    @Test
    void aRouteCountsDepotToFirstEachLegAndTheReturn() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, null, null);
        Visit b = stop(2, 1, null, null);
        route(van, a, b);

        // almacén→a 1.112 + a→b 1.112 + b→almacén 2.224
        verifier.verifyThat(RouteConstraints::totalDistance).given(van, a, b).penalizesBy(4448);
    }

    @Test
    void theOrderOfTheStopsChangesTheDistance() {
        RouteVehicle van = van(10, 20 * 3600);
        Visit a = stop(1, 1, null, null);
        Visit b = stop(2, 1, null, null);
        Visit c = stop(3, 1, null, null);

        route(van, a, b, c);
        // Ir hacia fuera y volver: 1.112 + 1.112 + 1.112 + 3.336.
        verifier.verifyThat(RouteConstraints::totalDistance).given(van, a, b, c).penalizesBy(6672);

        route(van, b, a, c);
        // Zigzag: 2.224 + 1.112 + 2.224 + 3.336.
        verifier.verifyThat(RouteConstraints::totalDistance).given(van, a, b, c).penalizesBy(8896);
    }

    @Test
    void anIdleVanAddsNoDistance() {
        RouteVehicle idle = van(10, 20 * 3600);

        verifier.verifyThat(RouteConstraints::totalDistance).given(idle).penalizesBy(0);
    }
}
