package com.rutaflex.api.job;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las transiciones de estado son UPDATE con la condición de estado en el WHERE: la base de datos decide, de
 * forma atómica, si el mensaje aplica. Devuelven si se cambió algo; un mensaje repetido o tardío cambia 0 filas.
 */
@Repository
class OptimizationJobRepository {

    /** Fila tal cual está en la base de datos; {@code result} es el JSON de las rutas. */
    record Row(UUID id, UUID warehouseId, LocalDate deliveryDate, JobStatus status, String failureReason,
               String result, BigDecimal totalKm, BigDecimal baselineKm, OffsetDateTime requestedAt,
               OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    private static final String COLUMNS = """
            id, warehouse_id, delivery_date, status, failure_reason, result::text AS result, total_km, baseline_km,
            requested_at, started_at, finished_at""";

    private final JdbcClient jdbc;

    OptimizationJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID warehouseId, LocalDate deliveryDate) {
        return jdbc.sql("""
                        INSERT INTO optimization_job (warehouse_id, delivery_date)
                        VALUES (:warehouse, :date) RETURNING id""")
                .param("warehouse", warehouseId)
                .param("date", deliveryDate)
                .query(UUID.class)
                .single();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM optimization_job WHERE id = :id")
                .param("id", id)
                .query(OptimizationJobRepository::map)
                .optional();
    }

    /** Los más recientes primero; con {@code date} solo los de ese día. */
    List<Row> recent(LocalDate date, int limit) {
        String filter = date == null ? "" : "WHERE delivery_date = :date ";
        var statement = jdbc.sql("SELECT " + COLUMNS + " FROM optimization_job " + filter
                + "ORDER BY requested_at DESC, id LIMIT :limit");
        if (date != null) {
            statement = statement.param("date", date);
        }
        return statement.param("limit", limit).query(OptimizationJobRepository::map).list();
    }

    boolean markRunning(UUID id) {
        return jdbc.sql("""
                        UPDATE optimization_job SET status = 'RUNNING', started_at = now()
                         WHERE id = :id AND status = 'QUEUED'""")
                .param("id", id)
                .update() == 1;
    }

    boolean markCompleted(UUID id, String resultJson, BigDecimal totalKm, BigDecimal baselineKm) {
        return jdbc.sql("""
                        UPDATE optimization_job
                           SET status = 'COMPLETED', result = CAST(:result AS jsonb), total_km = :total,
                               baseline_km = :baseline, finished_at = now(),
                               started_at = COALESCE(started_at, now())
                         WHERE id = :id AND status IN ('QUEUED', 'RUNNING')""")
                .param("id", id)
                .param("result", resultJson)
                .param("total", totalKm)
                .param("baseline", baselineKm)
                .update() == 1;
    }

    boolean markFailed(UUID id, String reason) {
        return jdbc.sql("""
                        UPDATE optimization_job SET status = 'FAILED', failure_reason = :reason, finished_at = now()
                         WHERE id = :id AND status IN ('QUEUED', 'RUNNING')""")
                .param("id", id)
                .param("reason", reason)
                .update() == 1;
    }

    /** Marca FAILED los trabajos activos que llevan mas de {@code seconds} segundos; devuelve cuantos. */
    int failStale(long seconds, String reason) {
        return jdbc.sql("""
                        UPDATE optimization_job SET status = 'FAILED', failure_reason = :reason, finished_at = now()
                         WHERE status IN ('QUEUED', 'RUNNING')
                           AND requested_at < now() - make_interval(secs => :seconds)""")
                .param("reason", reason)
                .param("seconds", seconds)
                .update();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getObject("delivery_date", LocalDate.class),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                rs.getString("result"),
                rs.getBigDecimal("total_km"),
                rs.getBigDecimal("baseline_km"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }
}
