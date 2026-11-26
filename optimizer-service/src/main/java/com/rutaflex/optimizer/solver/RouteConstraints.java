package com.rutaflex.optimizer.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

/**
 * Restricciones del reparto.
 * <ul>
 *   <li>Duras (hacen la solución inviable): capacidad de la furgoneta, franja horaria de cada parada y
 *       fin de la jornada (la furgoneta debe estar de vuelta en el almacén).</li>
 *   <li>Blanda: minimizar los metros recorridos, contando salida y vuelta al almacén.</li>
 * </ul>
 * Una furgoneta sin paradas no sale del almacén y no cuenta distancia.
 */
public class RouteConstraints implements ConstraintProvider {

    public static final String VEHICLE_CAPACITY = "Vehicle capacity";
    public static final String TIME_WINDOW = "Time window";
    public static final String SHIFT_END = "Return before end of shift";
    public static final String TOTAL_DISTANCE = "Total distance";

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                vehicleCapacity(factory),
                timeWindow(factory),
                shiftEnd(factory),
                totalDistance(factory),
        };
    }

    /** Penaliza los bultos que sobran por encima de la capacidad. */
    Constraint vehicleCapacity(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null)
                .groupBy(Visit::getVehicle, ConstraintCollectors.sum(Visit::getParcels))
                .filter((vehicle, parcels) -> parcels > vehicle.getCapacityParcels())
                .penalize(HardSoftScore.ONE_HARD, (vehicle, parcels) -> parcels - vehicle.getCapacityParcels())
                .asConstraint(VEHICLE_CAPACITY);
    }

    /** Llegar antes de que abra la franja solo implica esperar; llegar después de que cierre es inviable. */
    Constraint timeWindow(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.getArrivalTime() != null && visit.hasWindow()
                        && visit.getArrivalTime() > visit.getWindowEnd())
                .penalize(HardSoftScore.ONE_HARD, visit -> minutesLate(visit.getArrivalTime() - visit.getWindowEnd()))
                .asConstraint(TIME_WINDOW);
    }

    /** La última parada determina cuándo vuelve la furgoneta al almacén. */
    Constraint shiftEnd(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.getNext() == null
                        && visit.getArrivalTime() != null && returnTime(visit) > visit.getVehicle().getShiftEnd())
                .penalize(HardSoftScore.ONE_HARD,
                        visit -> minutesLate(returnTime(visit) - visit.getVehicle().getShiftEnd()))
                .asConstraint(SHIFT_END);
    }

    /** Tramos entre paradas (y desde el almacén a la primera) más el tramo de vuelta desde la última. */
    Constraint totalDistance(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null)
                .penalize(HardSoftScore.ONE_SOFT, RouteConstraints::legMeters)
                .asConstraint(TOTAL_DISTANCE);
    }

    /** Metros del tramo que llega a esta parada, más el de vuelta al almacén si es la última. */
    static int legMeters(Visit visit) {
        RouteVehicle vehicle = visit.getVehicle();
        int incoming = visit.getPrevious() == null
                ? vehicle.distanceDepot(visit)
                : vehicle.distance(visit.getPrevious(), visit);
        int outgoing = visit.getNext() == null ? vehicle.distanceDepot(visit) : 0;
        return incoming + outgoing;
    }

    static int returnTime(Visit last) {
        RouteVehicle vehicle = last.getVehicle();
        return last.departureTime() + vehicle.travelSecondsFromDepot(last);
    }

    /** Minutos de retraso, redondeando hacia arriba: un segundo tarde ya es tarde. */
    private static int minutesLate(int seconds) {
        return (seconds + 59) / 60;
    }
}
