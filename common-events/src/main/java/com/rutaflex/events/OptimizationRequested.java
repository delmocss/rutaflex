package com.rutaflex.events;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Evento {@code optimization.requested}. Lleva el problema completo porque el optimizador no
 * accede a la base de datos: la API es la unica que la toca.
 *
 * @param jobId        id del trabajo; tambien sirve de clave de idempotencia en el consumidor
 * @param deliveryDate dia de reparto
 */
public record OptimizationRequested(
        UUID jobId,
        LocalDate deliveryDate,
        Warehouse warehouse,
        List<Vehicle> vehicles,
        List<Stop> stops) {

    public record GeoPoint(double lat, double lon) {
    }

    public record Warehouse(UUID id, String name, GeoPoint location) {
    }

    /** Capacidad en bultos; la jornada limita cuando puede salir y cuando debe haber vuelto. */
    public record Vehicle(UUID id, String name, int capacityParcels, LocalTime shiftStart, LocalTime shiftEnd) {
    }

    /** Franja horaria opcional: {@code windowStart} y {@code windowEnd} son ambas nulas o ambas informadas. */
    public record Stop(UUID id, String address, GeoPoint location, int parcels,
                       LocalTime windowStart, LocalTime windowEnd) {
    }
}
