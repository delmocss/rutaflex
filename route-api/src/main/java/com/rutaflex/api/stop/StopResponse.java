package com.rutaflex.api.stop;

import com.rutaflex.api.common.GeoPoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StopResponse(
        UUID id,
        LocalDate deliveryDate,
        String address,
        GeoPoint location,
        int parcels,
        LocalTime windowStart,
        LocalTime windowEnd,
        Instant createdAt) {
}
