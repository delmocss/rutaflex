package com.rutaflex.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.api.AbstractApiTest;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationCompleted.RouteStop;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationStarted;
import com.rutaflex.events.Topics;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Lo que el optimizador devuelve por Kafka. Cada test publica mensajes reales en el broker y espera a que el
 * listener los aplique. Para comprobar que algo se ignoró no basta con esperar: tras el mensaje a probar se
 * publica otro "testigo" en el mismo topic (que Kafka entrega en orden) y, cuando el testigo ya se aplicó, el
 * anterior ya se procesó.
 */
class JobResultsTest extends AbstractApiTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ObjectMapper json;

    // ---- ayudas ----

    private UUID insertJob(String status) {
        UUID warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouse (id, name, address, location) VALUES (?, 'W', 'A', "
                + "ST_SetSRID(ST_MakePoint(-3.65, 40.19), 4326)::geography)", warehouse);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_job (id, warehouse_id, delivery_date, status, failure_reason) "
                + "VALUES (?, ?, '2026-12-14', ?, ?)", id, warehouse, status,
                "FAILED".equals(status) ? "previous failure" : null);
        return id;
    }

    private void publish(String topic, UUID jobId, Object event) throws Exception {
        publishRaw(topic, jobId.toString(), json.writeValueAsString(event));
    }

    private void publishRaw(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get();
    }

    private OptimizationCompleted completed(UUID jobId, double totalKm) {
        UUID vehicle = UUID.randomUUID();
        return new OptimizationCompleted(jobId,
                List.of(new VehicleRoute(vehicle, totalKm, List.of(
                        new RouteStop(UUID.randomUUID(), 1, LocalTime.of(9, 5)),
                        new RouteStop(UUID.randomUUID(), 2, LocalTime.of(9, 40))))),
                totalKm, 100.0, 1234);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM optimization_job WHERE id = ?", String.class, id);
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo(expected));
    }

    /** Publica un trabajo testigo y espera a que se aplique: todo lo publicado antes en ese topic ya se procesó. */
    private void drainCompleted() throws Exception {
        UUID witness = insertJob("QUEUED");
        publish(Topics.OPTIMIZATION_COMPLETED, witness, completed(witness, 1));
        awaitStatus(witness, "COMPLETED");
    }

    private void drainStarted() throws Exception {
        UUID witness = insertJob("QUEUED");
        publish(Topics.OPTIMIZATION_STARTED, witness, new OptimizationStarted(witness));
        awaitStatus(witness, "RUNNING");
    }

    private void drainFailed() throws Exception {
        UUID witness = insertJob("QUEUED");
        publish(Topics.OPTIMIZATION_FAILED, witness, new OptimizationFailed(witness, "witness", 1));
        awaitStatus(witness, "FAILED");
    }

    // ---- camino feliz ----

    @Test
    void startedMovesTheJobToRunning() throws Exception {
        UUID id = insertJob("QUEUED");

        publish(Topics.OPTIMIZATION_STARTED, id, new OptimizationStarted(id));

        awaitStatus(id, "RUNNING");
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM optimization_job WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void completedStoresTheRoutesAndTheTotalsAndTheApiServesThem() throws Exception {
        UUID id = insertJob("RUNNING");
        OptimizationCompleted event = completed(id, 42.5);

        publish(Topics.OPTIMIZATION_COMPLETED, id, event);

        awaitStatus(id, "COMPLETED");
        mvc.perform(get("/api/optimizations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalKm").value(42.5))
                .andExpect(jsonPath("$.baselineKm").value(100.0))
                .andExpect(jsonPath("$.computeMillis").value(1234))
                .andExpect(jsonPath("$.finishedAt").exists())
                .andExpect(jsonPath("$.routes[0].vehicleId").value(event.routes().get(0).vehicleId().toString()))
                .andExpect(jsonPath("$.routes[0].stops[0].sequence").value(1))
                .andExpect(jsonPath("$.routes[0].stops[0].estimatedArrival").value("09:05:00"))
                .andExpect(jsonPath("$.routes[0].stops[1].sequence").value(2));
    }

    @Test
    void failedStoresTheReason() throws Exception {
        UUID id = insertJob("RUNNING");

        publish(Topics.OPTIMIZATION_FAILED, id, new OptimizationFailed(id, "No feasible plan", 3));

        awaitStatus(id, "FAILED");
        mvc.perform(get("/api/optimizations/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("No feasible plan"))
                .andExpect(jsonPath("$.routes").doesNotExist());
    }

    @Test
    void ifTheResultArrivesBeforeTheStartNoticeTheJobStillCompletesWithAStartTime() throws Exception {
        UUID id = insertJob("QUEUED");

        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 10));

        awaitStatus(id, "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM optimization_job WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    // ---- idempotencia: Kafka entrega al menos una vez ----

    @Test
    void theSameResultDeliveredTwiceChangesNothing() throws Exception {
        UUID id = insertJob("RUNNING");
        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 42.5));
        awaitStatus(id, "COMPLETED");
        Map<String, Object> before = jdbc.queryForMap("SELECT finished_at, total_km, result::text AS result "
                + "FROM optimization_job WHERE id = ?", id);

        // El duplicado trae otras cifras: si se aplicara, se notaría.
        publish(Topics.OPTIMIZATION_COMPLETED, id, completed(id, 99));
        drainCompleted();

        assertThat(jdbc.queryForMap("SELECT finished_at, total_km, result::text AS result "
                + "FROM optimization_job WHERE id = ?", id)).isEqualTo(before);
    }

    @Test
    void theSameFailureDeliveredTwiceKeepsTheFirstReason() throws Exception {
        UUID id = insertJob("RUNNING");
        publish(Topics.OPTIMIZATION_FAILED, id, new OptimizationFailed(id, "first reason", 1));
        awaitStatus(id, "FAILED");

        publish(Topics.OPTIMIZATION_FAILED, id, new OptimizationFailed(id, "second reason", 3));
        drainFailed();

        assertThat(jdbc.queryForObject("SELECT failure_reason FROM optimization_job WHERE id = ?", String.class, id))
                .isEqualTo("first reason");
    }

    // ---- mensajes tardíos o fuera de orden ----

    @Test
    void aLateStartNoticeNeverUndoesAFinalState() throws Exception {
        UUID id = insertJob("COMPLETED");

        publish(Topics.OPTIMIZATION_STARTED, id, new OptimizationStarted(id));
        drainStarted();

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    @Test
    void aResultNeverOverwritesAFailedJobNorTheOtherWayRound() throws Exception {
        UUID failed = insertJob("FAILED");
        UUID done = insertJob("COMPLETED");

        publish(Topics.OPTIMIZATION_COMPLETED, failed, completed(failed, 5));
        publish(Topics.OPTIMIZATION_FAILED, done, new OptimizationFailed(done, "late", 1));
        drainCompleted();
        drainFailed();

        assertThat(statusOf(failed)).isEqualTo("FAILED");
        assertThat(statusOf(done)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM optimization_job WHERE id = ?", String.class,
                failed)).isEqualTo("previous failure");
    }

    @Test
    void anEventForAnUnknownJobIsIgnoredAndDoesNotBlockTheOnesAfterIt() throws Exception {
        UUID ghost = UUID.randomUUID();
        UUID real = insertJob("QUEUED");

        publish(Topics.OPTIMIZATION_COMPLETED, ghost, completed(ghost, 5));
        publish(Topics.OPTIMIZATION_COMPLETED, real, completed(real, 7));

        awaitStatus(real, "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_job WHERE id = ?", Integer.class, ghost))
                .isZero();
    }

    // ---- mensajes ilegibles ----

    @Test
    void anUnreadableMessageIsDiscardedAndDoesNotBlockTheTopic() throws Exception {
        publishRaw(Topics.OPTIMIZATION_COMPLETED, "bad", "this is not json");
        publishRaw(Topics.OPTIMIZATION_COMPLETED, "bad", "{\"jobId\": \"not-a-uuid\"}");
        UUID real = insertJob("QUEUED");

        publish(Topics.OPTIMIZATION_COMPLETED, real, completed(real, 7));

        awaitStatus(real, "COMPLETED");
    }

    @Test
    void aFailureWithoutAReasonStillFailsTheJob() throws Exception {
        UUID id = insertJob("QUEUED");

        publishRaw(Topics.OPTIMIZATION_FAILED, id.toString(), "{\"jobId\": \"" + id + "\", \"attempts\": 3}");

        awaitStatus(id, "FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM optimization_job WHERE id = ?", String.class, id))
                .isEqualTo("The optimization failed");
    }
}
