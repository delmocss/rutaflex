package com.rutaflex.api.warehouse;

import com.rutaflex.api.common.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 200) String address,
        @NotNull @Valid GeoPoint location) {
}
