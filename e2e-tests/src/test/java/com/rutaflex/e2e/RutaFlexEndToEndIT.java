package com.rutaflex.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prueba de extremo a extremo: PostGIS y Kafka reales y los dos servicios empaquetados (los jars ejecutables
 * que se desplegarían), cada uno en su propio proceso, hablando solo por Kafka. Nada se simula.
 *
 * <p>El día 2026-12-14 viene del dataset de ejemplo de la API (80 paradas); cada test que necesita datos
 * propios usa su propio día, así que los tests no dependen del orden.
 */
class RutaFlexEndToEndIT {

    private static final String SAMPLE_DAY = "2026-12-14";
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(120);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static PostgreSQLContainer<?> postgis;
    private static KafkaContainer kafka;
    private static Process routeApi;
    private static Process optimizer;
    private static Path logs;
    private static String api;

    @BeforeAll
    static void startEverything() throws Exception {
        postgis = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
        postgis.start();
        kafka.start();

        logs = Path.of(System.getProperty("rutaflex.logs", "target/e2e-logs"));
        Files.createDirectories(logs);
        int optimizerPort = freePort();
        int apiPort = freePort();
        api = "http://localhost:" + apiPort;

        optimizer = launch("optimizer", System.getProperty("rutaflex.jar.optimizer"),
                Map.of("PORT", String.valueOf(optimizerPort),
                        "KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers(),
                        // Cálculos cortos para que la prueba sea rápida; la calidad no es lo que se prueba aquí.
                        "OPTIMIZER_SPENT_LIMIT", "8s",
                        "OPTIMIZER_UNIMPROVED_LIMIT", "3s"));
        routeApi = launch("route-api", System.getProperty("rutaflex.jar.route-api"),
                Map.of("PORT", String.valueOf(apiPort),
                        "DB_URL", postgis.getJdbcUrl(),
                        "DB_USER", postgis.getUsername(),
                        "DB_PASSWORD", postgis.getPassword(),
                        "KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers()),
                "--rutaflex.sample-data.enabled=true", "--rutaflex.sample-data.delivery-date=" + SAMPLE_DAY);

        awaitHealthy("optimizer", "http://localhost:" + optimizerPort, optimizer);
        awaitHealthy("route-api", api, routeApi);
    }

    @AfterAll
    static void stopEverything() {
        for (Process process : new Process[] {routeApi, optimizer}) {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (kafka != null) {
            kafka.stop();
        }
        if (postgis != null) {
            postgis.stop();
        }
    }

    // ---- el flujo completo ----

    @Test
    void aWholeDayIsOptimisedAcrossBothServicesThroughKafka() throws Exception {
        JsonNode accepted = post("/api/optimizations", "{\"deliveryDate\": \"" + SAMPLE_DAY + "\"}", 202);
        String id = accepted.get("id").asText();
        assertThat(accepted.get("status").asText()).isEqualTo("QUEUED");

        JsonNode job = awaitFinished(id);

        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("startedAt").isNull()).isFalse();
        assertThat(job.get("computeMillis").asLong()).isPositive();
        assertThat(job.get("totalKm").asDouble()).isLessThan(job.get("baselineKm").asDouble());

        // Todas las paradas del dia, cada una exactamente una vez.
        Set<String> expected = new HashSet<>();
        Map<String, JsonNode> stopsById = new HashMap<>();
        for (JsonNode stop : get("/api/stops?date=" + SAMPLE_DAY)) {
            expected.add(stop.get("id").asText());
            stopsById.put(stop.get("id").asText(), stop);
        }
        List<String> assigned = new ArrayList<>();
        Map<String, JsonNode> vehiclesById = new HashMap<>();
        String warehouse = job.get("warehouseId").asText();
        for (JsonNode vehicle : get("/api/warehouses/" + warehouse + "/vehicles")) {
            vehiclesById.put(vehicle.get("id").asText(), vehicle);
        }
        assertThat(expected).hasSize(80);

        for (JsonNode route : job.get("routes")) {
            int parcels = 0;
            for (JsonNode stop : route.get("stops")) {
                String stopId = stop.get("stopId").asText();
                assigned.add(stopId);
                JsonNode data = stopsById.get(stopId);
                parcels += data.get("parcels").asInt();
                if (data.hasNonNull("windowStart")) {
                    LocalTime arrival = LocalTime.parse(stop.get("estimatedArrival").asText());
                    assertThat(arrival).isBetween(LocalTime.parse(data.get("windowStart").asText()),
                            LocalTime.parse(data.get("windowEnd").asText()));
                }
            }
            JsonNode vehicle = vehiclesById.get(route.get("vehicleId").asText());
            assertThat(parcels).isLessThanOrEqualTo(vehicle.get("capacityParcels").asInt());
        }
        assertThat(assigned).containsExactlyInAnyOrderElementsOf(expected);
        System.out.printf("E2E 80 stops: baseline %.1f km -> optimised %.1f km in %d ms%n",
                job.get("baselineKm").asDouble(), job.get("totalKm").asDouble(), job.get("computeMillis").asLong());
    }

    @Test
    void anImpossibleTimeWindowEndsAsAFailedJobWithTheReason() throws Exception {
        // A unos 24 km del almacen y con la franja de 08:00 a 08:01: ninguna furgoneta puede llegar a tiempo.
        addStop("2026-12-16", "Muy lejos", 40.42, -3.60, 1, "\"windowStart\": \"08:00\", \"windowEnd\": \"08:01\"");

        JsonNode job = awaitFinished(request("2026-12-16"));

        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("failureReason").asText()).contains("No feasible plan");
        assertThat(job.has("routes")).isFalse();
    }

    @Test
    void aStopBiggerThanAnyVanEndsAsAFailedJobWithTheReason() throws Exception {
        addStop("2026-12-17", "Enorme", 40.21, -3.65, 100, null);

        JsonNode job = awaitFinished(request("2026-12-17"));

        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("failureReason").asText()).contains("more than any single vehicle");
    }

    // ---- Kafka entrega al menos una vez: duplicados y basura ----

    @Test
    void aDuplicatedRequestIsNotRecomputedAndTheJobStaysTheSame() throws Exception {
        addStop("2026-12-18", "Uno", 40.21, -3.65, 2, null);
        addStop("2026-12-18", "Dos", 40.22, -3.66, 3, null);
        addStop("2026-12-18", "Tres", 40.23, -3.67, 1, null);
        String id = request("2026-12-18");
        JsonNode first = awaitFinished(id);
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");

        // Se reenvia exactamente el mismo mensaje, como haria Kafka ante una entrega duplicada.
        ConsumerRecord<String, String> original = recordsOf("optimization.requested").stream()
                .filter(r -> id.equals(r.key())).findFirst().orElseThrow();
        publish("optimization.requested", original.key(), original.value());

        // Un trabajo testigo en el mismo topic: cuando termina, el duplicado ya se atendio (el orden se conserva).
        addStop("2026-12-19", "Testigo", 40.2, -3.66, 1, null);
        assertThat(awaitFinished(request("2026-12-19")).get("status").asText()).isEqualTo("COMPLETED");

        JsonNode after = get("/api/optimizations/" + id);
        assertThat(after.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(after.get("finishedAt")).isEqualTo(first.get("finishedAt"));
        assertThat(after.get("totalKm")).isEqualTo(first.get("totalKm"));
        // La prueba de que no se recalculo: el optimizador solo publico un resultado para ese trabajo.
        assertThat(recordsOf("optimization.completed").stream().filter(r -> id.equals(r.key())).count()).isEqualTo(1);
    }

    @Test
    void garbageOnTheRequestTopicDoesNotBlockTheOnesAfterIt() throws Exception {
        publish("optimization.requested", "basura", "esto no es json");
        publish("optimization.requested", "basura", "{\"jobId\": \"no-es-un-uuid\"}");
        addStop("2026-12-20", "Uno", 40.21, -3.65, 1, null);
        addStop("2026-12-20", "Dos", 40.22, -3.66, 1, null);

        JsonNode job = awaitFinished(request("2026-12-20"));

        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("routes")).isNotEmpty();
    }

    @Test
    void aSecondRequestForADayWithAnActiveJobIsRefused() throws Exception {
        addStop("2026-12-21", "Uno", 40.21, -3.65, 1, null);
        // Un trabajo RUNNING ya existente para ese dia, insertado directamente: sin depender de cuanto tarde el solver.
        insertJob("2026-12-21", "RUNNING");

        HttpResponse<String> second = send("POST", "/api/optimizations", "{\"deliveryDate\": \"2026-12-21\"}");

        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
    }

    @Test
    void anUnreadableRequestForAKnownJobFailsThatJobInsteadOfLeavingItWaiting() throws Exception {
        // Un trabajo QUEUED cuyo mensaje llega ilegible (por ejemplo, por versiones distintas de los servicios).
        String id = insertJob("2026-12-22", "QUEUED");
        publish("optimization.requested", id, "{\"jobId\": \"" + id + "\", \"deliveryDate\": \"sin sentido\"}");

        JsonNode job = awaitFinished(id);

        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("failureReason").asText()).contains("could not be read");
    }

    // ---- ayudas ----

    private static String request(String day) throws Exception {
        return post("/api/optimizations", "{\"deliveryDate\": \"" + day + "\"}", 202).get("id").asText();
    }

    private static void addStop(String day, String address, double lat, double lon, int parcels, String window)
            throws Exception {
        post("/api/stops", """
                {"deliveryDate": "%s", "address": "%s", "location": {"lat": %s, "lon": %s}, "parcels": %d%s}"""
                .formatted(day, address, lat, lon, parcels, window == null ? "" : ", " + window), 201);
    }

    private static JsonNode awaitFinished(String id) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        JsonNode job = null;
        while (System.nanoTime() < deadline) {
            job = get("/api/optimizations/" + id);
            String status = job.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("The job did not finish in " + JOB_TIMEOUT + ": " + job + "\n" + tail("optimizer")
                + "\n" + tail("route-api"));
    }

    private static JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode post(String path, String body, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("POST", path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(api + path))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30));
        request = body == null ? request.method(method, HttpRequest.BodyPublishers.noBody())
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Inserta un trabajo directamente en la base de datos y devuelve su id. */
    private static String insertJob(String day, String status) throws Exception {
        String warehouse = get("/api/warehouses").get(0).get("id").asText();
        String id = UUID.randomUUID().toString();
        try (Connection db = DriverManager.getConnection(postgis.getJdbcUrl(), postgis.getUsername(),
                postgis.getPassword());
             PreparedStatement insert = db.prepareStatement("INSERT INTO optimization_job "
                     + "(id, warehouse_id, delivery_date, status) VALUES (?::uuid, ?::uuid, ?::date, ?)")) {
            insert.setString(1, id);
            insert.setString(2, warehouse);
            insert.setString(3, day);
            insert.setString(4, status);
            insert.executeUpdate();
        }
        return id;
    }

    // ---- Kafka directo ----

    private static void publish(String topic, String key, String value) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** Todo lo publicado en el topic desde el principio (sin grupo ni confirmaciones). */
    private static List<ConsumerRecord<String, String>> recordsOf(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
            }
        }
        return all;
    }

    // ---- procesos ----

    private static Process launch(String name, String jar, Map<String, String> env, String... args)
            throws IOException {
        Path file = Path.of(jar).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new IllegalStateException(file + " does not exist: run 'mvn verify' from the repository root so "
                    + "the services are packaged before this module");
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", file.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(logs.resolve(name + ".log").toFile());
        builder.environment().putAll(env);
        return builder.start();
    }

    private static void awaitHealthy(String name, String baseUrl, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited early:\n" + tail(name));
            }
            try {
                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException e) {
                // Todavia arrancando.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(name + " did not become healthy:\n" + tail(name));
    }

    private static String tail(String name) {
        try {
            List<String> lines = Files.readAllLines(logs.resolve(name + ".log"));
            return "--- " + name + " log (last lines) ---\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log for " + name + ")";
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
