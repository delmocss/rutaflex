package com.rutaflex.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El contrato entre servicios: los eventos viajan como JSON por Kafka y deben volver iguales. */
class EventsContractTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void topicNamesAreDistinct() {
        Set<String> names = new HashSet<>(List.of(
                Topics.OPTIMIZATION_REQUESTED, Topics.OPTIMIZATION_COMPLETED, Topics.OPTIMIZATION_FAILED));
        assertThat(names).hasSize(3);
    }

    @Test
    void requestedSurvivesJsonRoundTrip() throws Exception {
        var origin = new OptimizationRequested.GeoPoint(40.19, -3.68);
        var event = new OptimizationRequested(
                UUID.randomUUID(),
                LocalDate.of(2026, 12, 14),
                new OptimizationRequested.Warehouse(UUID.randomUUID(), "Valdemoro", origin),
                List.of(new OptimizationRequested.Vehicle(
                        UUID.randomUUID(), "Furgoneta 1", 40, LocalTime.of(8, 0), LocalTime.of(15, 0))),
                List.of(
                        new OptimizationRequested.Stop(UUID.randomUUID(), "Calle Mayor 1, Pinto",
                                new OptimizationRequested.GeoPoint(40.24, -3.70), 3,
                                LocalTime.of(9, 0), LocalTime.of(11, 0)),
                        new OptimizationRequested.Stop(UUID.randomUUID(), "Calle Real 5, Getafe",
                                new OptimizationRequested.GeoPoint(40.31, -3.73), 1, null, null)));

        String text = json.writeValueAsString(event);

        assertThat(text).contains("\"deliveryDate\":\"2026-12-14\"").contains("\"windowStart\":\"09:00:00\"");
        assertThat(json.readValue(text, OptimizationRequested.class)).isEqualTo(event);
    }

    @Test
    void completedAndFailedSurviveJsonRoundTrip() throws Exception {
        var completed = new OptimizationCompleted(
                UUID.randomUUID(),
                List.of(new OptimizationCompleted.VehicleRoute(UUID.randomUUID(), 21.5,
                        List.of(new OptimizationCompleted.RouteStop(UUID.randomUUID(), 1, LocalTime.of(9, 12))))),
                21.5, 34.0, 1800);
        var failed = new OptimizationFailed(UUID.randomUUID(), "sin solucion factible", 3);

        assertThat(json.readValue(json.writeValueAsString(completed), OptimizationCompleted.class))
                .isEqualTo(completed);
        assertThat(json.readValue(json.writeValueAsString(failed), OptimizationFailed.class)).isEqualTo(failed);
    }
}
