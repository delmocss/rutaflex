package com.rutaflex.api.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.api.common.ConflictException;
import com.rutaflex.api.common.NotFoundException;
import com.rutaflex.api.common.ServiceUnavailableException;
import com.rutaflex.api.common.UnprocessableException;
import com.rutaflex.api.stop.StopResponse;
import com.rutaflex.api.stop.StopService;
import com.rutaflex.api.vehicle.VehicleResponse;
import com.rutaflex.api.vehicle.VehicleService;
import com.rutaflex.api.warehouse.WarehouseResponse;
import com.rutaflex.api.warehouse.WarehouseService;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationRequested.GeoPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Pide optimizaciones y refleja su avance. Pedir es asíncrono: se guarda el trabajo (QUEUED), se publica el
 * problema en Kafka y se responde sin esperar al cálculo. El resultado vuelve por Kafka y lo aplican los
 * métodos {@code on*}, que son idempotentes: Kafka entrega al menos una vez, así que el mismo mensaje puede
 * llegar repetido, y un mensaje tardío no debe deshacer un estado final.
 *
 * <p>Limitación conocida: el trabajo se guarda y el mensaje se publica en dos pasos. Si el proceso muere entre
 * ambos, el trabajo se queda en QUEUED sin mensaje. El patrón outbox lo resuelve y es la mejora prevista.
 */
@Service
public class OptimizationJobService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationJobService.class);
    private static final int MAX_REASON_LENGTH = 1000;

    /** Lo que se guarda en la columna {@code result}. */
    record StoredResult(List<VehicleRoute> routes, long computeMillis) {
    }

    private final OptimizationJobRepository jobs;
    private final WarehouseService warehouses;
    private final VehicleService vehicles;
    private final StopService stops;
    private final OptimizationRequestPublisher publisher;
    private final ObjectMapper json;

    OptimizationJobService(OptimizationJobRepository jobs, WarehouseService warehouses, VehicleService vehicles,
                           StopService stops, OptimizationRequestPublisher publisher, ObjectMapper json) {
        this.jobs = jobs;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.stops = stops;
        this.publisher = publisher;
        this.json = json;
    }

    public JobResponse request(LocalDate deliveryDate, UUID warehouseId) {
        WarehouseResponse warehouse = resolveWarehouse(warehouseId);
        List<VehicleResponse> fleet = vehicles.listByWarehouse(warehouse.id());
        if (fleet.isEmpty()) {
            throw new UnprocessableException("The warehouse has no vehicles to plan with");
        }
        List<StopResponse> dayStops = stops.listByDate(deliveryDate);
        if (dayStops.isEmpty()) {
            throw new UnprocessableException("There are no stops for " + deliveryDate);
        }

        UUID id;
        try {
            id = jobs.insert(warehouse.id(), deliveryDate);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("An optimization for this warehouse and day is already in progress");
        }

        try {
            publisher.publish(problem(id, deliveryDate, warehouse, fleet, dayStops));
        } catch (RuntimeException e) {
            // El trabajo no esta realmente en cola: se deja constancia y se avisa, en vez de dejarlo colgado.
            jobs.markFailed(id, "The job could not be queued: the message broker is unavailable");
            throw new ServiceUnavailableException("The optimization could not be queued, try again shortly", e);
        }
        return get(id);
    }

    public JobResponse get(UUID id) {
        return jobs.find(id).map(row -> toResponse(row, true)).orElseThrow(() -> new NotFoundException("Job", id));
    }

    public List<JobResponse> recent(LocalDate date, int limit) {
        return jobs.recent(date, limit).stream().map(row -> toResponse(row, false)).toList();
    }

    // ---- resultados que llegan por Kafka (idempotentes) ----

    public void onStarted(UUID jobId) {
        record(jobId, "started", jobs.markRunning(jobId));
    }

    public void onCompleted(OptimizationCompleted event) {
        String result;
        try {
            result = json.writeValueAsString(new StoredResult(event.routes(), event.computeMillis()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the optimization result", e);
        }
        record(event.jobId(), "completed", jobs.markCompleted(event.jobId(), result,
                BigDecimal.valueOf(event.totalKm()), BigDecimal.valueOf(event.baselineKm())));
    }

    public void onFailed(UUID jobId, String reason) {
        String text = reason == null || reason.isBlank() ? "The optimization failed" : reason;
        if (text.length() > MAX_REASON_LENGTH) {
            text = text.substring(0, MAX_REASON_LENGTH);
        }
        record(jobId, "failed", jobs.markFailed(jobId, text));
    }

    private static void record(UUID jobId, String event, boolean applied) {
        if (applied) {
            log.info("Job {}: {} applied", jobId, event);
        } else {
            log.info("Job {}: {} ignored (duplicate, late or unknown job)", jobId, event);
        }
    }

    // ---- ayudas ----

    private WarehouseResponse resolveWarehouse(UUID warehouseId) {
        if (warehouseId != null) {
            return warehouses.get(warehouseId);
        }
        List<WarehouseResponse> all = warehouses.list();
        if (all.isEmpty()) {
            throw new UnprocessableException("There is no warehouse yet");
        }
        if (all.size() > 1) {
            throw new UnprocessableException("warehouseId is required when there is more than one warehouse");
        }
        return all.get(0);
    }

    private static OptimizationRequested problem(UUID jobId, LocalDate date, WarehouseResponse warehouse,
                                                 List<VehicleResponse> fleet, List<StopResponse> dayStops) {
        return new OptimizationRequested(
                jobId,
                date,
                new OptimizationRequested.Warehouse(warehouse.id(), warehouse.name(),
                        new GeoPoint(warehouse.location().lat(), warehouse.location().lon())),
                fleet.stream()
                        .map(v -> new OptimizationRequested.Vehicle(v.id(), v.name(), v.capacityParcels(),
                                v.shiftStart(), v.shiftEnd()))
                        .toList(),
                dayStops.stream()
                        .map(s -> new OptimizationRequested.Stop(s.id(), s.address(),
                                new GeoPoint(s.location().lat(), s.location().lon()), s.parcels(),
                                s.windowStart(), s.windowEnd()))
                        .toList());
    }

    private JobResponse toResponse(OptimizationJobRepository.Row row, boolean withRoutes) {
        Long computeMillis = null;
        List<VehicleRoute> routes = null;
        if (withRoutes && row.result() != null) {
            try {
                StoredResult stored = json.readValue(row.result(), StoredResult.class);
                computeMillis = stored.computeMillis();
                routes = stored.routes();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The stored result of job " + row.id() + " is unreadable", e);
            }
        }
        return new JobResponse(row.id(), row.warehouseId(), row.deliveryDate(), row.status(), row.failureReason(),
                row.totalKm(), row.baselineKm(), row.requestedAt().toInstant(),
                row.startedAt() == null ? null : row.startedAt().toInstant(),
                row.finishedAt() == null ? null : row.finishedAt().toInstant(), computeMillis, routes);
    }
}
