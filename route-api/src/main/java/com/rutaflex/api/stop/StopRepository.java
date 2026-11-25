package com.rutaflex.api.stop;

import com.rutaflex.api.common.GeoPoint;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class StopRepository {

    private static final String COLUMNS = """
            id, delivery_date, address, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon,
            parcels, window_start, window_end, created_at""";

    private static final String INSERT = """
            INSERT INTO delivery_stop (delivery_date, address, location, parcels, window_start, window_end)
            VALUES (:date, :address, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                    :parcels, :windowStart, :windowEnd)""";

    private static final RowMapper<StopResponse> MAPPER = (rs, row) -> new StopResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("delivery_date", LocalDate.class),
            rs.getString("address"),
            new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon")),
            rs.getInt("parcels"),
            rs.getObject("window_start", LocalTime.class),
            rs.getObject("window_end", LocalTime.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchJdbc;

    StopRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    StopResponse insert(StopRequest request) {
        return jdbc.sql(INSERT + " RETURNING " + COLUMNS)
                .paramSource(params(request))
                .query(MAPPER)
                .single();
    }

    /** Inserta todas en una sola ida a la base de datos; la atomicidad la da la transaccion del servicio. */
    void insertAll(List<StopRequest> requests) {
        SqlParameterSource[] batch = requests.stream().map(StopRepository::params).toArray(SqlParameterSource[]::new);
        batchJdbc.batchUpdate(INSERT, batch);
    }

    List<StopResponse> findByDate(LocalDate date) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM delivery_stop WHERE delivery_date = :date ORDER BY created_at, id")
                .param("date", date)
                .query(MAPPER)
                .list();
    }

    List<StopDateSummary> summarizeByDate() {
        return jdbc.sql("""
                        SELECT delivery_date, count(*) AS stops, sum(parcels) AS parcels
                        FROM delivery_stop GROUP BY delivery_date ORDER BY delivery_date""")
                .query((rs, row) -> new StopDateSummary(
                        rs.getObject("delivery_date", LocalDate.class), rs.getInt("stops"), rs.getInt("parcels")))
                .list();
    }

    Optional<StopResponse> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM delivery_stop WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    Optional<StopResponse> update(UUID id, StopRequest request) {
        return jdbc.sql("""
                        UPDATE delivery_stop
                        SET delivery_date = :date, address = :address,
                            location = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                            parcels = :parcels, window_start = :windowStart, window_end = :windowEnd
                        WHERE id = :id
                        RETURNING\s""" + COLUMNS)
                .paramSource(params(request).addValue("id", id))
                .query(MAPPER)
                .optional();
    }

    boolean deleteById(UUID id) {
        return jdbc.sql("DELETE FROM delivery_stop WHERE id = :id").param("id", id).update() > 0;
    }

    int deleteByDate(LocalDate date) {
        return jdbc.sql("DELETE FROM delivery_stop WHERE delivery_date = :date").param("date", date).update();
    }

    private static MapSqlParameterSource params(StopRequest request) {
        return new MapSqlParameterSource()
                .addValue("date", request.deliveryDate())
                .addValue("address", request.address())
                .addValue("lon", request.location().lon())
                .addValue("lat", request.location().lat())
                .addValue("parcels", request.parcels())
                .addValue("windowStart", request.windowStart())
                .addValue("windowEnd", request.windowEnd());
    }
}
