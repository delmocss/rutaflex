package com.rutaflex.api.vehicle;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** Capacidad en bultos y jornada: la furgoneta sale a {@code shiftStart} y debe haber vuelto a {@code shiftEnd}. */
public record VehicleRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull @Min(1) @Max(10_000) Integer capacityParcels,
        @NotNull LocalTime shiftStart,
        @NotNull LocalTime shiftEnd) {

    @JsonIgnore
    @AssertTrue(message = "shiftEnd must be after shiftStart")
    boolean isShiftOrdered() {
        return shiftStart == null || shiftEnd == null || shiftStart.isBefore(shiftEnd);
    }
}
