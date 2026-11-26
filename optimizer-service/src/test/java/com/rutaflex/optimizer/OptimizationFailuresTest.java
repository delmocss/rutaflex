package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationRequested.GeoPoint;
import com.rutaflex.events.OptimizationRequested.Stop;
import com.rutaflex.events.OptimizationRequested.Vehicle;
import com.rutaflex.events.OptimizationRequested.Warehouse;
import com.rutaflex.events.OptimizationStarted;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RouteSolver;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * Los caminos de fallo: qué pasa con lo que no se puede leer, con lo muy antiguo y con un Kafka que no responde.
 * La idea común: un trabajo identificable nunca se queda esperando sin que la API se entere.
 */
class OptimizationFailuresTest {

    static class Recorder implements EventPublisher {
        final List<Object> events = new ArrayList<>();
        int failFailedTimes;
        int failCompletedTimes;
        int optimizations;

        @Override
        public void started(OptimizationStarted event) {
            events.add(event);
        }

        @Override
        public void completed(OptimizationCompleted event) {
            if (failCompletedTimes-- > 0) {
                throw new IllegalStateException("kafka down");
            }
            events.add(event);
        }

        @Override
        public void failed(OptimizationFailed event) {
            if (failFailedTimes-- > 0) {
                throw new IllegalStateException("kafka down");
            }
            events.add(event);
        }

        List<OptimizationFailed> failures() {
            return events.stream().filter(OptimizationFailed.class::isInstance).map(OptimizationFailed.class::cast)
                    .toList();
        }
    }

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Recorder publisher = new Recorder();
    private final FailureReporter reporter = new FailureReporter(publisher, json);

    /** Cuenta los cálculos reales para poder afirmar que un reintento no recalcula. */
    private RouteOptimizer countingOptimizer() {
        RouteOptimizer real = new RouteOptimizer(new RouteSolver(Duration.ofSeconds(2), Duration.ofSeconds(1), 1L),
                Assumptions.DEFAULT);
        return new RouteOptimizer(new RouteSolver(Duration.ofSeconds(2), Duration.ofSeconds(1), 1L),
                Assumptions.DEFAULT) {
            @Override
            public OptimizationCompleted optimize(OptimizationRequested request) {
                publisher.optimizations++;
                return real.optimize(request);
            }
        };
    }

    private static OptimizationRequested request() {
        return new OptimizationRequested(UUID.randomUUID(), LocalDate.of(2026, 12, 14),
                new Warehouse(UUID.randomUUID(), "Almacen", new GeoPoint(40.1985, -3.656)),
                List.of(new Vehicle(UUID.randomUUID(), "V1", 60, LocalTime.of(8, 0), LocalTime.of(15, 0))),
                List.of(new Stop(UUID.randomUUID(), "uno", new GeoPoint(40.21, -3.65), 2, null, null),
                        new Stop(UUID.randomUUID(), "dos", new GeoPoint(40.22, -3.66), 2, null, null)));
    }

    // ---- FailureReporter ----

    @Test
    void theJobIsIdentifiedByTheMessageKey() {
        UUID id = UUID.randomUUID();

        boolean reported = reporter.report(id.toString(), "not json at all", "boom", 3);

        assertThat(reported).isTrue();
        assertThat(publisher.failures()).containsExactly(new OptimizationFailed(id, "boom", 3));
    }

    @Test
    void ifTheKeyIsNotAnIdTheBodyIsTried() {
        UUID id = UUID.randomUUID();

        reporter.report("garbage-key", "{\"jobId\": \"" + id + "\", \"deliveryDate\": \"nonsense\"}", "boom", 1);

        assertThat(publisher.failures()).extracting(OptimizationFailed::jobId).containsExactly(id);
    }

    @Test
    void withNoJobIdAnywhereNothingIsPublishedAndNothingThrows() {
        assertThat(reporter.report("garbage", "not json", "boom", 1)).isFalse();
        assertThat(reporter.report(null, null, "boom", 1)).isFalse();
        assertThat(reporter.report(null, "{\"jobId\": 42}", "boom", 1)).isFalse();
        assertThat(publisher.failures()).isEmpty();
    }

    @Test
    void ifPublishingTheNoticeFailsTheReporterSwallowsItSoTheErrorHandlerDoesNotLoop() {
        publisher.failFailedTimes = 1;

        boolean reported = reporter.report(UUID.randomUUID().toString(), null, "boom", 1);

        assertThat(reported).isFalse();
    }

    // ---- listener ----

    private OptimizationListener listener() {
        return new OptimizationListener(new OptimizationHandler(countingOptimizer(), publisher), reporter, json);
    }

    private ConsumerRecord<String, String> record(String key, String value, Instant at) {
        return new ConsumerRecord<>("optimization.requested", 0, 0, at.toEpochMilli(),
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, key, value,
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    @Test
    void aReadableRequestIsProcessed() throws Exception {
        OptimizationRequested request = request();

        listener().onRequested(record(request.jobId().toString(), json.writeValueAsString(request), Instant.now()));

        assertThat(publisher.events).hasAtLeastOneElementOfType(OptimizationCompleted.class);
        assertThat(publisher.failures()).isEmpty();
    }

    @Test
    void anUnreadableRequestWithAKnownJobFailsThatJobInsteadOfLeavingItWaiting() {
        UUID id = UUID.randomUUID();

        listener().onRequested(record(id.toString(), "{\"jobId\": \"" + id + "\", \"deliveryDate\": \"nonsense\"}",
                Instant.now()));

        assertThat(publisher.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.jobId()).isEqualTo(id);
            assertThat(failure.reason()).contains("could not be read");
        });
        assertThat(publisher.optimizations).isZero();
    }

    @Test
    void aRequestWithoutJobIdIsReportedUnderItsKey() {
        UUID id = UUID.randomUUID();

        listener().onRequested(record(id.toString(), "{}", Instant.now()));

        assertThat(publisher.failures()).extracting(OptimizationFailed::jobId).containsExactly(id);
    }

    @Test
    void completeGarbageIsDiscardedQuietly() {
        listener().onRequested(record("garbage", "esto no es json", Instant.now()));

        assertThat(publisher.events).isEmpty();
    }

    // ---- gestor de errores y reintentos ----

    @Test
    void anOldRequestIsIgnoredBecauseTheApiHasAlreadyClosedItsJob() throws Exception {
        OptimizationRequested request = request();

        listener().onRequested(record(request.jobId().toString(), json.writeValueAsString(request),
                Instant.now().minus(Duration.ofHours(2))));

        assertThat(publisher.events).isEmpty();
        assertThat(publisher.optimizations).isZero();
    }

    @Test
    void whenKafkaIsDownAfterTheSolveTheRetryRepublishesWithoutRecomputing() {
        OptimizationHandler handler = new OptimizationHandler(countingOptimizer(), publisher);
        OptimizationRequested request = request();
        publisher.failCompletedTimes = 2;

        assertThatThrownBy(() -> handler.handle(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> handler.handle(request)).isInstanceOf(IllegalStateException.class);
        handler.handle(request);

        assertThat(publisher.optimizations).as("the solve ran once, not once per attempt").isEqualTo(1);
        assertThat(publisher.events).hasAtLeastOneElementOfType(OptimizationCompleted.class);
    }
}
