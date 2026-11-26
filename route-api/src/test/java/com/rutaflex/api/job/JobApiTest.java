package com.rutaflex.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.rutaflex.api.AbstractApiTest;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.Topics;
import java.time.Duration;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class JobApiTest extends AbstractApiTest {

    private static final String DAY = "2026-12-14";

    @Autowired
    ObjectMapper json;

    // ---- ayudas de datos ----

    private String createWarehouse(String name) throws Exception {
        String body = mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "address": "Valdemoro", "location": {"lat": 40.1985, "lon": -3.656}}"""
                        .formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void createVehicle(String warehouseId, String name, int capacity) throws Exception {
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "capacityParcels": %d, "shiftStart": "08:00", "shiftEnd": "15:00"}"""
                                .formatted(name, capacity)))
                .andExpect(status().isCreated());
    }

    private void createStop(String address, double lat, double lon, int parcels, String window) throws Exception {
        String windowJson = window == null ? "" : ", " + window;
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                        {"deliveryDate": "%s", "address": "%s", "location": {"lat": %s, "lon": %s},
                         "parcels": %d%s}""".formatted(DAY, address, lat, lon, parcels, windowJson)))
                .andExpect(status().isCreated());
    }

    /** Un almacén con dos furgonetas y tres paradas (la segunda con franja horaria). */
    private String seedProblem() throws Exception {
        String warehouse = createWarehouse("Almacen");
        createVehicle(warehouse, "Furgoneta 1", 40);
        createVehicle(warehouse, "Furgoneta 2", 30);
        createStop("Primera", 40.21, -3.65, 2, null);
        createStop("Segunda", 40.22, -3.66, 3, "\"windowStart\": \"09:00\", \"windowEnd\": \"11:00\"");
        createStop("Tercera", 40.23, -3.67, 1, null);
        return warehouse;
    }

    private String requestJob(String body) throws Exception {
        String response = mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    // ---- pedir una optimización ----

    @Test
    void requestingReturns202WithLocationAndAQueuedJob() throws Exception {
        String warehouse = seedProblem();

        String response = mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"" + DAY + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/optimizations/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.warehouseId").value(warehouse))
                .andExpect(jsonPath("$.deliveryDate").value(DAY))
                .andExpect(jsonPath("$.routes").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(response, "$.id");
        assertThat(jdbc.queryForObject("SELECT status FROM optimization_job WHERE id = ?::uuid", String.class, id))
                .isEqualTo("QUEUED");
    }

    @Test
    void thePublishedMessageCarriesTheWholeProblemWithStopsInTheirOriginalOrder() throws Exception {
        String warehouse = seedProblem();
        String id = requestJob("{\"deliveryDate\": \"" + DAY + "\"}");

        String payload = KafkaProbe.valueForKey(KAFKA.getBootstrapServers(), Topics.OPTIMIZATION_REQUESTED, id,
                Duration.ofSeconds(15)).orElseThrow(() -> new AssertionError("no message was published"));
        OptimizationRequested event = json.readValue(payload, OptimizationRequested.class);

        assertThat(event.jobId()).isEqualTo(UUID.fromString(id));
        assertThat(event.deliveryDate()).hasToString(DAY);
        assertThat(event.warehouse().id()).isEqualTo(UUID.fromString(warehouse));
        assertThat(event.warehouse().location().lat()).isEqualTo(40.1985);
        assertThat(event.vehicles()).extracting(OptimizationRequested.Vehicle::name)
                .containsExactlyInAnyOrder("Furgoneta 1", "Furgoneta 2");
        assertThat(event.vehicles()).extracting(OptimizationRequested.Vehicle::capacityParcels)
                .containsExactlyInAnyOrder(40, 30);
        assertThat(event.stops()).extracting(OptimizationRequested.Stop::address)
                .containsExactly("Primera", "Segunda", "Tercera");
        assertThat(event.stops().get(1).windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(event.stops().get(1).windowEnd()).isEqualTo(LocalTime.of(11, 0));
        assertThat(event.stops().get(0).windowStart()).isNull();
    }

    @Test
    void theWarehouseCanBeChosenExplicitlyWhenThereAreSeveral() throws Exception {
        String first = seedProblem();
        createWarehouse("Otro");

        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"" + DAY + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("warehouseId is required when there is more than one warehouse"));

        requestJob("{\"deliveryDate\": \"" + DAY + "\", \"warehouseId\": \"" + first + "\"}");
    }

    // ---- peticiones que no se pueden atender ----

    @Test
    void aMissingOrMalformedDateIsABadRequest() throws Exception {
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.deliveryDate").exists());
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"14/12/2026\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutWarehouseVehiclesOrStopsThereIsNothingToPlan() throws Exception {
        String body = "{\"deliveryDate\": \"" + DAY + "\"}";
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("There is no warehouse yet"));

        String warehouse = createWarehouse("Almacen");
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("The warehouse has no vehicles to plan with"));

        createVehicle(warehouse, "Furgoneta 1", 40);
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("There are no stops for " + DAY));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_job", Integer.class)).isZero();
    }

    @Test
    void anUnknownWarehouseIsNotFound() throws Exception {
        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"" + DAY + "\", \"warehouseId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSecondRequestWhileOneIsActiveIsAConflict() throws Exception {
        seedProblem();
        String body = "{\"deliveryDate\": \"" + DAY + "\"}";
        requestJob(body);

        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An optimization for this warehouse and day is already in progress"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void onceTheJobIsFinishedANewOneCanBeRequestedForTheSameDay() throws Exception {
        seedProblem();
        String body = "{\"deliveryDate\": \"" + DAY + "\"}";
        String first = requestJob(body);
        jdbc.update("UPDATE optimization_job SET status = 'FAILED', failure_reason = 'x' WHERE id = ?::uuid", first);

        String second = requestJob(body);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void differentDaysDoNotBlockEachOther() throws Exception {
        String warehouse = seedProblem();
        requestJob("{\"deliveryDate\": \"" + DAY + "\"}");
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                {"deliveryDate": "2026-12-15", "address": "Otro dia", "location": {"lat": 40.2, "lon": -3.7},
                 "parcels": 1}"""));

        requestJob("{\"deliveryDate\": \"2026-12-15\", \"warehouseId\": \"" + warehouse + "\"}");
    }

    // ---- consultar ----

    @Test
    void aJobCanBeFetchedByIdAndUnknownIdsAre404() throws Exception {
        seedProblem();
        String id = requestJob("{\"deliveryDate\": \"" + DAY + "\"}");

        mvc.perform(get("/api/optimizations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.requestedAt").exists())
                .andExpect(jsonPath("$.startedAt").doesNotExist());
        mvc.perform(get("/api/optimizations/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/optimizations/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void recentJobsCanBeFilteredByDayAndLimited() throws Exception {
        String warehouse = seedProblem();
        String first = requestJob("{\"deliveryDate\": \"" + DAY + "\"}");
        jdbc.update("UPDATE optimization_job SET status = 'COMPLETED' WHERE id = ?::uuid", first);
        String second = requestJob("{\"deliveryDate\": \"" + DAY + "\"}");
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                {"deliveryDate": "2026-12-15", "address": "Otro dia", "location": {"lat": 40.2, "lon": -3.7},
                 "parcels": 1}"""));
        requestJob("{\"deliveryDate\": \"2026-12-15\", \"warehouseId\": \"" + warehouse + "\"}");

        mvc.perform(get("/api/optimizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/optimizations").param("date", DAY))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second));
        mvc.perform(get("/api/optimizations").param("limit", "1")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/optimizations").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/optimizations").param("limit", "500")).andExpect(status().isBadRequest());
    }
}
