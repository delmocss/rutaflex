package com.rutaflex.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.api.AbstractApiTest;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationCompleted.RouteStop;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Las métricas de las optimizaciones. El registro es compartido por todos los tests del contexto, así que cada
 * test mide la diferencia (antes/después) en vez de valores absolutos.
 */
class JobMetricsTest extends AbstractApiTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ObjectMapper json;

    // ---- lecturas del registro ----

    private long count(String name) {
        var timer = registry.find(name).timer();
        var summary = registry.find(name).summary();
        return timer != null ? timer.count() : summary != null ? summary.count() : 0;
    }

    private double total(String name) {
        var summary = registry.find(name).summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private double finished(String result) {
        var counter = registry.find("rutaflex.jobs.finished").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- ayudas ----

    private UUID insertJob(String status) {
        UUID warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouse (id, name, address, location) VALUES (?, 'W', 'A', "
                + "ST_SetSRID(ST_MakePoint(-3.65, 40.19), 4326)::geography)", warehouse);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_job (id, warehouse_id, delivery_date, status, failure_reason) "
                + "VALUES (?, ?, '2026-12-14', ?, ?)", id, warehouse, status, "FAILED".equals(status) ? "x" : null);
        return id;
    }

    private void publish(String topic, UUID id, Object event) throws Exception {
        kafka.send(topic, id.toString(), json.writeValueAsString(event)).get();
    }

    private OptimizationCompleted completed(UUID id, double totalKm, double baselineKm, long millis) {
        return new OptimizationCompleted(id, List.of(new VehicleRoute(UUID.randomUUID(), totalKm,
                List.of(new RouteStop(UUID.randomUUID(), 1, LocalTime.of(9, 0))))), totalKm, baselineKm, millis);
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM optimization_job WHERE id = ?", String.class, id))
                .isEqualTo(expected));
    }

    // ---- tests ----

    @Test
    void aCompletedJobRecordsItsComputeTimeAndTheKmItSaves() throws Exception {
        long timers = count("rutaflex.optimization.compute");
        double saved = total("rutaflex.optimization.km.saved");
        double percent = total("rutaflex.optimization.saved.percent");
        double done = finished("completed");
        UUID id = insertJob("RUNNING");

        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 120, 800, 8000));
        awaitStatus(id, "COMPLETED");

        assertThat(count("rutaflex.optimization.compute")).isEqualTo(timers + 1);
        assertThat(registry.find("rutaflex.optimization.compute").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(8000);
        assertThat(total("rutaflex.optimization.km.saved") - saved).isEqualTo(680.0);
        assertThat(total("rutaflex.optimization.saved.percent") - percent).isEqualTo(85.0);
        assertThat(finished("completed") - done).isEqualTo(1.0);
    }

    @Test
    void aDuplicatedResultIsCountedOnlyOnce() throws Exception {
        double done = finished("completed");
        long timers = count("rutaflex.optimization.compute");
        UUID id = insertJob("RUNNING");
        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 100, 500, 1000));
        awaitStatus(id, "COMPLETED");

        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 100, 500, 1000));
        // Un trabajo testigo en el mismo topic: cuando se aplica, el duplicado ya se proceso.
        UUID witness = insertJob("QUEUED");
        publish(Topics.OPTIMIZATION_COMPLETED, witness, completed(witness, 50, 100, 10));
        awaitStatus(witness, "COMPLETED");

        assertThat(finished("completed") - done).as("original + testigo, no el duplicado").isEqualTo(2.0);
        assertThat(count("rutaflex.optimization.compute") - timers).isEqualTo(2);
    }

    @Test
    void aResultWorseThanTheBaselineSavesZeroKmNeverNegative() throws Exception {
        double saved = total("rutaflex.optimization.km.saved");
        long summaries = count("rutaflex.optimization.km.saved");
        UUID id = insertJob("RUNNING");

        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 300, 200, 1000));
        awaitStatus(id, "COMPLETED");

        assertThat(count("rutaflex.optimization.km.saved") - summaries).isEqualTo(1);
        assertThat(total("rutaflex.optimization.km.saved") - saved).isZero();
    }

    @Test
    void aFailedJobIsCountedAndALateFailureOnAFinishedJobIsNot() throws Exception {
        double failed = finished("failed");
        UUID running = insertJob("RUNNING");
        UUID finishedJob = insertJob("COMPLETED");

        publish(Topics.OPTIMIZATION_FAILED, running, new OptimizationFailed(running, "boom", 3));
        publish(Topics.OPTIMIZATION_FAILED, finishedJob, new OptimizationFailed(finishedJob, "late", 1));
        awaitStatus(running, "FAILED");
        UUID witness = insertJob("QUEUED");
        publish(Topics.OPTIMIZATION_FAILED, witness, new OptimizationFailed(witness, "witness", 1));
        awaitStatus(witness, "FAILED");

        assertThat(finished("failed") - failed).as("el fallo real y el testigo; el tardio no").isEqualTo(2.0);
    }

    @Test
    void requestingAnOptimizationIsCounted() throws Exception {
        double before = registry.find("rutaflex.jobs.requested").counter() == null
                ? 0 : registry.find("rutaflex.jobs.requested").counter().count();
        mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "A", "address": "B", "location": {"lat": 40.19, "lon": -3.65}}"""));
        String warehouse = jdbc.queryForObject("SELECT id::text FROM warehouse", String.class);
        mvc.perform(post("/api/warehouses/" + warehouse + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"V\", \"capacityParcels\": 10, \"shiftStart\": \"08:00\", \"shiftEnd\": \"15:00\"}"));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                {"deliveryDate": "2026-12-14", "address": "S", "location": {"lat": 40.2, "lon": -3.7}, "parcels": 1}"""));

        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryDate\": \"2026-12-14\"}")).andExpect(status().isAccepted());

        assertThat(registry.find("rutaflex.jobs.requested").counter().count() - before).isEqualTo(1.0);
    }

    @Test
    void theMetricsAreExposedInActuator() throws Exception {
        UUID id = insertJob("RUNNING");
        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 100, 400, 2000));
        awaitStatus(id, "COMPLETED");

        mvc.perform(get("/actuator/metrics/rutaflex.optimization.km.saved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("rutaflex.optimization.km.saved"))
                .andExpect(jsonPath("$.baseUnit").value("kilometers"))
                .andExpect(jsonPath("$.measurements[?(@.statistic=='COUNT')].value").isNotEmpty());
        mvc.perform(get("/actuator/metrics/rutaflex.optimization.compute")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.names", org.hamcrest.Matchers.hasItems("rutaflex.optimization.compute",
                        "rutaflex.optimization.km.saved", "rutaflex.optimization.saved.percent",
                        "rutaflex.jobs.finished")));
    }
}
