package com.rutaflex.optimizer.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.CascadingUpdateShadowVariable;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.NextElementShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;
import java.util.UUID;

/**
 * Una parada. Es el "valor" que el solver reparte en las listas de las furgonetas, y a la vez una
 * entidad porque lleva variables sombra (furgoneta, anterior, siguiente y hora de llegada).
 * Los tiempos son segundos desde medianoche.
 */
@PlanningEntity
public class Visit {

    @PlanningId
    private UUID id;
    private String address;
    private double lat;
    private double lon;
    private int parcels;
    /** Franja horaria; ambos nulos si la parada no tiene. */
    private Integer windowStart;
    private Integer windowEnd;
    private int serviceSeconds;

    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private RouteVehicle vehicle;

    @PreviousElementShadowVariable(sourceVariableName = "visits")
    private Visit previous;

    @NextElementShadowVariable(sourceVariableName = "visits")
    private Visit next;

    /** Hora a la que la furgoneta llega (sin contar la espera a que abra la franja). */
    @CascadingUpdateShadowVariable(targetMethodName = "updateArrivalTime")
    private Integer arrivalTime;

    public Visit() {
    }

    public Visit(UUID id, String address, double lat, double lon, int parcels, Integer windowStart,
                 Integer windowEnd, Assumptions assumptions) {
        this.id = id;
        this.address = address;
        this.lat = lat;
        this.lon = lon;
        this.parcels = parcels;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.serviceSeconds = assumptions.serviceSeconds(parcels);
    }

    /** Lo invoca el solver cada vez que cambia la lista de la furgoneta; también se usa directamente en los tests. */
    @SuppressWarnings("unused")
    public void updateArrivalTime() {
        if (vehicle == null) {
            arrivalTime = null;
        } else if (previous == null) {
            arrivalTime = vehicle.getShiftStart() + vehicle.travelSecondsFromDepot(this);
        } else if (previous.arrivalTime == null) {
            arrivalTime = null;
        } else {
            arrivalTime = previous.departureTime() + vehicle.travelSeconds(previous, this);
        }
    }

    /** Hora en que empieza el servicio: si se llega antes de que abra la franja, se espera. */
    public int serviceStart() {
        return windowStart == null ? arrivalTime : Math.max(arrivalTime, windowStart);
    }

    /** Hora en que la furgoneta sale hacia la siguiente parada. */
    public int departureTime() {
        return serviceStart() + serviceSeconds;
    }

    public boolean hasWindow() {
        return windowStart != null && windowEnd != null;
    }

    public UUID getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public int getParcels() {
        return parcels;
    }

    public Integer getWindowStart() {
        return windowStart;
    }

    public Integer getWindowEnd() {
        return windowEnd;
    }

    public int getServiceSeconds() {
        return serviceSeconds;
    }

    public RouteVehicle getVehicle() {
        return vehicle;
    }

    public void setVehicle(RouteVehicle vehicle) {
        this.vehicle = vehicle;
    }

    public Visit getPrevious() {
        return previous;
    }

    public void setPrevious(Visit previous) {
        this.previous = previous;
    }

    public Visit getNext() {
        return next;
    }

    public void setNext(Visit next) {
        this.next = next;
    }

    public Integer getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(Integer arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    @Override
    public String toString() {
        return address;
    }
}
