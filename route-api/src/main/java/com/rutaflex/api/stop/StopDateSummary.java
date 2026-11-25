package com.rutaflex.api.stop;

import java.time.LocalDate;

/** Dias con paradas y cuantas hay en cada uno: lo que necesita un selector de fecha. */
public record StopDateSummary(LocalDate date, int stops, int parcels) {
}
