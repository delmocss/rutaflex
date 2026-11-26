package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La lógica de atender una petición, sin Kafka: un publicador que anota lo que se le pide. */
class OptimizationHandlerTest {

    /** Anota lo publicado, en orden; puede configurarse para fallar un número de veces. */
    static class RecordingPublisher implements EventPublisher {
        final List<String> log = new ArrayList<>();
        final List<Object> events = new ArrayList<>();
        int failCompletedTimes;
        boolean failStarted;

        @Override
        public void started(OptimizationStarted event) {
            if (failStarted) {
                throw new IllegalStateException("broker down");
            }
            log.add("started");
            events.add(event);
        }

        @Override
        public void completed(OptimizationCompleted event) {
            if (failCompletedTimes-- > 0) {
                throw new IllegalStateException("broker down");
            }
            log.add("completed");
            events.add(event);
        }

        @Override
        public void failed(OptimizationFailed event) {
            log.add("failed");
            events.add(event);
        }
    }

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final OptimizationHandler handler = new OptimizationHandler(
            new RouteOptimizer(new RouteSolver(Duration.ofSeconds(2), Duration.ofSeconds(1), 1L),
                    Assumptions.DEFAULT),
            publisher);

    private static OptimizationRequested request(int parcelsPerStop, int capacity) {
        List<Stop> stops = List.of(
                new Stop(UUID.randomUUID(), "uno", new GeoPoint(40.21, -3.65), parcelsPerStop, null, null),
                new Stop(UUID.randomUUID(), "dos", new GeoPoint(40.22, -3.66), parcelsPerStop, null, null),
                new Stop(UUID.randomUUID(), "tres", new GeoPoint(40.23, -3.67), parcelsPerStop, null, null));
        return new OptimizationRequested(UUID.randomUUID(), LocalDate.of(2026, 12, 14),
                new Warehouse(UUID.randomUUID(), "Almacen", new GeoPoint(40.1985, -3.656)),
                List.of(new Vehicle(UUID.randomUUID(), "V1", capacity, LocalTime.of(8, 0), LocalTime.of(15, 0))),
                stops);
    }

    @Test
    void aSolvableRequestPublishesStartedThenCompleted() {
        OptimizationRequested request = request(2, 60);

        handler.handle(request);

        assertThat(publisher.log).containsExactly("started", "completed");
        assertThat(((OptimizationStarted) publisher.events.get(0)).jobId()).isEqualTo(request.jobId());
        var completed = (OptimizationCompleted) publisher.events.get(1);
        assertThat(completed.jobId()).isEqualTo(request.jobId());
        assertThat(completed.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(3);
    }

    @Test
    void theSameRequestDeliveredTwiceIsOnlyProcessedOnce() {
        OptimizationRequested request = request(2, 60);

        handler.handle(request);
        handler.handle(request);

        assertThat(publisher.log).containsExactly("started", "completed");
    }

    @Test
    void anUnsolvableRequestPublishesFailedWithTheReasonAndIsNotRetried() {
        // 3 paradas de 30 bultos y una furgoneta de 60: no caben.
        OptimizationRequested request = request(30, 60);

        handler.handle(request);

        assertThat(publisher.log).containsExactly("started", "failed");
        var failed = (OptimizationFailed) publisher.events.get(1);
        assertThat(failed.jobId()).isEqualTo(request.jobId());
        assertThat(failed.reason()).contains("90 parcels but the vehicles only carry 60");
        assertThat(failed.attempts()).isEqualTo(1);
    }

    @Test
    void aTransientFailureIsRethrownAndTheRetryIsNotMistakenForADuplicate() {
        OptimizationRequested request = request(2, 60);
        publisher.failCompletedTimes = 1;

        assertThatThrownBy(() -> handler.handle(request)).isInstanceOf(IllegalStateException.class);
        assertThat(publisher.log).doesNotContain("completed");

        handler.handle(request); // el reintento del gestor de errores de Kafka

        assertThat(publisher.log).endsWith("completed");
        assertThat(publisher.log.stream().filter("completed"::equals)).hasSize(1);
    }

    @Test
    void ifEvenStartedCannotBePublishedTheFailureIsRethrownToo() {
        OptimizationRequested request = request(2, 60);
        publisher.failStarted = true;

        assertThatThrownBy(() -> handler.handle(request)).isInstanceOf(IllegalStateException.class);

        publisher.failStarted = false;
        handler.handle(request);
        assertThat(publisher.log).containsExactly("started", "completed");
    }

    @Test
    void differentJobsAreIndependent() {
        handler.handle(request(2, 60));
        handler.handle(request(2, 60));

        assertThat(publisher.log).containsExactly("started", "completed", "started", "completed");
    }
}
