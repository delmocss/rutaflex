package com.rutaflex.optimizer.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Una furgoneta y la lista ordenada de paradas que el solver le asigna. Los tiempos son segundos desde medianoche. */
@PlanningEntity
public class RouteVehicle {

    @PlanningId
    private UUID id;
    private String name;
    private int capacityParcels;
    private int shiftStart;
    private int shiftEnd;
    private double depotLat;
    private double depotLon;
    private Assumptions assumptions;

    @PlanningListVariable
    private List<Visit> visits = new ArrayList<>();

    public RouteVehicle() {
    }

    public RouteVehicle(UUID id, String name, int capacityParcels, int shiftStart, int shiftEnd, double depotLat,
                        double depotLon, Assumptions assumptions) {
        this.id = id;
        this.name = name;
        this.capacityParcels = capacityParcels;
        this.shiftStart = shiftStart;
        this.shiftEnd = shiftEnd;
        this.depotLat = depotLat;
        this.depotLon = depotLon;
        this.assumptions = assumptions;
    }

    /** Distancia entre el almacén y una parada (es simétrica, sirve para ir y para volver). */
    public int distanceDepot(Visit visit) {
        return assumptions.distanceMeters(depotLat, depotLon, visit.getLat(), visit.getLon());
    }

    public int distance(Visit from, Visit to) {
        return assumptions.distanceMeters(from.getLat(), from.getLon(), to.getLat(), to.getLon());
    }

    public int travelSecondsFromDepot(Visit visit) {
        return assumptions.travelSeconds(distanceDepot(visit));
    }

    public int travelSeconds(Visit from, Visit to) {
        return assumptions.travelSeconds(distance(from, to));
    }

    public int totalParcels() {
        return visits.stream().mapToInt(Visit::getParcels).sum();
    }

    /** Hora a la que la furgoneta vuelve al almacén tras la última parada (0 si no sale). */
    public int returnTime() {
        if (visits.isEmpty()) {
            return 0;
        }
        Visit last = visits.get(visits.size() - 1);
        return last.departureTime() + assumptions.travelSeconds(distanceDepot(last));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getCapacityParcels() {
        return capacityParcels;
    }

    public int getShiftStart() {
        return shiftStart;
    }

    public int getShiftEnd() {
        return shiftEnd;
    }

    public List<Visit> getVisits() {
        return visits;
    }

    public void setVisits(List<Visit> visits) {
        this.visits = visits;
    }

    @Override
    public String toString() {
        return name;
    }
}
