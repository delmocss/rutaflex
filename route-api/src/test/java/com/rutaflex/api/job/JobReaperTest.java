package com.rutaflex.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rutaflex.api.AbstractApiTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Con un plazo de 2 s y el vigilante cada 300 ms, comprueba también que está programado de verdad (sin llamarlo a mano). */
@TestPropertySource(properties = {"rutaflex.jobs.timeout=2s", "rutaflex.jobs.reaper-interval=300ms"})
class JobReaperTest extends AbstractApiTest {

    @Autowired
    MeterRegistry registry;

    /** Inserta un trabajo con la antigüedad indicada (en segundos) y devuelve su id. */
    private UUID insertJob(String status, int ageSeconds, String day) {
        UUID warehouse = jdbc.queryForObject("SELECT id FROM warehouse LIMIT 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_job (id, warehouse_id, delivery_date, status, requested_at) "
                + "VALUES (?, ?, ?::date, ?, now() - make_interval(secs => ?))", id, warehouse, day, status,
                ageSeconds);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM optimization_job WHERE id = ?", String.class, id);
    }

    private void warehouse() {
        jdbc.update("INSERT INTO warehouse (name, address, location) VALUES ('W', 'A', "
                + "ST_SetSRID(ST_MakePoint(-3.65, 40.19), 4326)::geography)");
    }

    @Test
    void staleActiveJobsAreFailedAndRecentOrFinishedOnesAreLeftAlone() {
        warehouse();
        UUID staleQueued = insertJob("QUEUED", 3600, "2026-12-01");
        UUID staleRunning = insertJob("RUNNING", 3600, "2026-12-02");
        UUID recent = insertJob("QUEUED", 0, "2026-12-03");
        UUID oldCompleted = insertJob("COMPLETED", 3600, "2026-12-04");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusOf(staleQueued)).isEqualTo("FAILED");
            assertThat(statusOf(staleRunning)).isEqualTo("FAILED");
        });

        assertThat(jdbc.queryForObject("SELECT failure_reason FROM optimization_job WHERE id = ?", String.class,
                staleQueued)).contains("did not finish within 2 seconds");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM optimization_job WHERE id = ?",
                Boolean.class, staleQueued)).isTrue();
        assertThat(statusOf(oldCompleted)).isEqualTo("COMPLETED");
        // Los trabajos cerrados por el vigilante quedan contados.
        assertThat(registry.find("rutaflex.jobs.reaped").counter().count()).isGreaterThanOrEqualTo(2.0);
        // El reciente aun esta dentro de plazo (el test termina antes de que cumpla 2 s).
        assertThat(statusOf(recent)).isIn("QUEUED", "FAILED");
    }

    @Test
    void afterTheJobIsClosedTheSameDayCanBeRequestedAgain() {
        warehouse();
        UUID stuck = insertJob("QUEUED", 3600, "2026-12-10");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(stuck)).isEqualTo("FAILED"));

        // El indice unico parcial ya no bloquea ese almacen y dia.
        UUID next = insertJob("QUEUED", 0, "2026-12-10");

        assertThat(statusOf(next)).isEqualTo("QUEUED");
    }
}
