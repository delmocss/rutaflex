package com.rutaflex.api.vehicle;

import java.time.LocalTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        UUID warehouseId,
        String name,
        int capacityParcels,
        LocalTime shiftStart,
        LocalTime shiftEnd) {
}
