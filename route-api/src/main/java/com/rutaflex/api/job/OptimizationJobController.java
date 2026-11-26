package com.rutaflex.api.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pedir una optimización devuelve 202 con el id: el cálculo ocurre después y se consulta con GET. */
@RestController
@Validated
class OptimizationJobController {

    private final OptimizationJobService service;

    OptimizationJobController(OptimizationJobService service) {
        this.service = service;
    }

    @PostMapping("/api/optimizations")
    ResponseEntity<JobResponse> request(@Valid @RequestBody OptimizationRequest request) {
        JobResponse job = service.request(request.deliveryDate(), request.warehouseId());
        return ResponseEntity.accepted().location(URI.create("/api/optimizations/" + job.id())).body(job);
    }

    @GetMapping("/api/optimizations/{id}")
    JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/api/optimizations")
    List<JobResponse> recent(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.recent(date, limit);
    }
}
