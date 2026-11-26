package com.rutaflex.api.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Estado de un trabajo. {@code routes} y {@code computeMillis} solo existen cuando está COMPLETED
 * (y los listados los omiten); {@code failureReason} solo cuando está FAILED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
        UUID id,
        UUID warehouseId,
        LocalDate deliveryDate,
        JobStatus status,
        String failureReason,
        BigDecimal totalKm,
        BigDecimal baselineKm,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Long computeMillis,
        List<VehicleRoute> routes) {
}
