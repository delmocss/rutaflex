package com.rutaflex.events;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Evento {@code optimization.completed}: rutas calculadas, una por furgoneta.
 *
 * @param totalKm       km de la solucion optimizada
 * @param baselineKm    km del reparto sin optimizar (paradas en orden de llegada, por turnos)
 * @param computeMillis tiempo de calculo, para la metrica de Micrometer
 */
public record OptimizationCompleted(
        UUID jobId,
        List<VehicleRoute> routes,
        double totalKm,
        double baselineKm,
        long computeMillis) {

    public record VehicleRoute(UUID vehicleId, double distanceKm, List<RouteStop> stops) {
    }

    /** {@code sequence} empieza en 1; {@code estimatedArrival} es la hora estimada de llegada. */
    public record RouteStop(UUID stopId, int sequence, LocalTime estimatedArrival) {
    }
}
