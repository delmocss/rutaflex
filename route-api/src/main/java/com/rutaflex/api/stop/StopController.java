package com.rutaflex.api.stop;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stops")
class StopController {

    private final StopService service;

    StopController(StopService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<StopResponse> create(@Valid @RequestBody StopRequest request) {
        StopResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/stops/" + created.id())).body(created);
    }

    /** Las paradas de un dia (la fecha es obligatoria: un reparto siempre es de un dia concreto). */
    @GetMapping
    List<StopResponse> listByDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.listByDate(date);
    }

    @GetMapping("/dates")
    List<StopDateSummary> dates() {
        return service.summarizeByDate();
    }

    @GetMapping("/{id}")
    StopResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    StopResponse update(@PathVariable UUID id, @Valid @RequestBody StopRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> deleteByDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteByDate(date);
        return ResponseEntity.noContent().build();
    }
}
