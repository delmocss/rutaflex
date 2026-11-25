package com.rutaflex.api.warehouse;

import com.rutaflex.api.common.GeoPoint;
import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(UUID id, String name, String address, GeoPoint location, Instant createdAt) {
}
