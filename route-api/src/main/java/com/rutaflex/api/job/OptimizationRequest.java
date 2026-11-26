package com.rutaflex.api.job;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** {@code warehouseId} es opcional si solo hay un almacén. */
public record OptimizationRequest(@NotNull LocalDate deliveryDate, UUID warehouseId) {
}
