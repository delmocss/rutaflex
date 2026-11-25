package com.rutaflex.api.stop;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rutaflex.api.common.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** Parada de un dia de reparto. La franja horaria es opcional, pero va completa o no va. */
public record StopRequest(
        @NotNull LocalDate deliveryDate,
        @NotBlank @Size(max = 200) String address,
        @NotNull @Valid GeoPoint location,
        @NotNull @Min(1) @Max(10_000) Integer parcels,
        LocalTime windowStart,
        LocalTime windowEnd) {

    @JsonIgnore
    @AssertTrue(message = "windowStart and windowEnd must be both set or both empty, and windowStart must be before windowEnd")
    boolean isWindowValid() {
        if (windowStart == null && windowEnd == null) {
            return true;
        }
        return windowStart != null && windowEnd != null && windowStart.isBefore(windowEnd);
    }
}
