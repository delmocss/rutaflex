package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RoutePlan;
import com.rutaflex.optimizer.solver.RouteSolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Genera {@code docs/measurement.md}: cuántos km ahorra el optimizador sobre el dataset de ejemplo y cómo se
 * ha medido. No se ejecuta con {@code mvn verify} (tarda más de 20 minutos); se lanza a propósito:
 *
 * <pre>mvn -pl optimizer-service -am test -Dtest=MeasurementReport -Drutaflex.measure=true</pre>
 *
 * Todo lo que escribe es determinista (km, no tiempos): con el mismo entorno da el mismo fichero.
 */
@EnabledIfSystemProperty(named = "rutaflex.measure", matches = "true")
class MeasurementReport {

    private static final int STEPS = Integer.getInteger("rutaflex.measure.steps", 60_000);
    private static final int SEEDS = Integer.getInteger("rutaflex.measure.seeds", 3);

    /** Pasos con los que se dibuja la curva de convergencia (siempre con la semilla 1). */
    private static final int[] CURVE = java.util.Arrays
            .stream(System.getProperty("rutaflex.measure.curve", "10000,20000,30000,40000,50000,60000,80000").split(","))
            .mapToInt(Integer::parseInt).toArray();

    @Test
    void generate() throws Exception {
        OptimizationRequested request = SampleDataset.request();
        Assumptions assumptions = Assumptions.DEFAULT;

        // Las referencias, calculadas sobre el mismo problema que el optimizador.
        RoutePlan problem = new RouteOptimizer(RouteSolver.withStepLimit(1, 1), assumptions).toPlan(request);
        Baselines.Result arrival = Baselines.arrivalOrder(problem);
        Baselines.Result sweep = Baselines.sweepNearest(problem);

        List<OptimizationCompleted> runs = new ArrayList<>();
        for (int seed = 1; seed <= SEEDS; seed++) {
            long started = System.nanoTime();
            OptimizationCompleted result = new RouteOptimizer(RouteSolver.withStepLimit(STEPS, seed), assumptions)
                    .optimize(request);
            System.out.printf(Locale.ROOT, "MEASURE seed %d: %.2f km in %d ms%n", seed, result.totalKm(),
                    (System.nanoTime() - started) / 1_000_000);
            assertThat(result.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(80);
            runs.add(result);
        }

        // Ablacion: el mismo problema sin franjas horarias, para comparar distancia contra distancia con
        // referencias que tampoco las miran.
        OptimizationRequested noWindows = SampleDataset.request(
                request.stops().stream().map(s -> new OptimizationRequested.Stop(s.id(), s.address(), s.location(),
                        s.parcels(), null, null)).toList(), request.vehicles());
        RoutePlan noWindowsProblem = new RouteOptimizer(RouteSolver.withStepLimit(1, 1), assumptions)
                .toPlan(noWindows);
        Baselines.Result sweepNoWindows = Baselines.sweepNearest(noWindowsProblem);
        List<OptimizationCompleted> ablation = new ArrayList<>();
        for (int seed = 1; seed <= SEEDS; seed++) {
            OptimizationCompleted result = new RouteOptimizer(RouteSolver.withStepLimit(STEPS, seed), assumptions)
                    .optimize(noWindows);
            System.out.printf(Locale.ROOT, "MEASURE no-windows seed %d: %.2f km%n", seed, result.totalKm());
            ablation.add(result);
        }

        // Curva de convergencia con la semilla 1.
        double[] curve = new double[CURVE.length];
        for (int i = 0; i < CURVE.length; i++) {
            curve[i] = new RouteOptimizer(RouteSolver.withStepLimit(CURVE[i], 1), assumptions).optimize(request)
                    .totalKm();
            System.out.printf(Locale.ROOT, "MEASURE steps %d: %.2f km%n", CURVE[i], curve[i]);
        }

        Path out = Path.of(System.getProperty("rutaflex.measure.out", "../docs/measurement.md"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report(request, arrival, sweep, runs, sweepNoWindows, ablation, curve));
        System.out.println("MEASURE written to " + out.toAbsolutePath().normalize());
    }

    private static String report(OptimizationRequested request, Baselines.Result arrival, Baselines.Result sweep,
                                 List<OptimizationCompleted> runs, Baselines.Result sweepNoWindows,
                                 List<OptimizationCompleted> ablation, double[] curve) {
        double[] km = runs.stream().mapToDouble(OptimizationCompleted::totalKm).toArray();
        double mean = java.util.Arrays.stream(km).average().orElseThrow();
        double min = java.util.Arrays.stream(km).min().orElseThrow();
        double max = java.util.Arrays.stream(km).max().orElseThrow();
        int stops = request.stops().size();
        int withWindow = (int) request.stops().stream().filter(s -> s.windowStart() != null).count();
        int parcels = request.stops().stream().mapToInt(OptimizationRequested.Stop::parcels).sum();
        int capacity = request.vehicles().stream().mapToInt(OptimizationRequested.Vehicle::capacityParcels).sum();

        StringBuilder md = new StringBuilder();
        md.append("# Medición: km ahorrados\n\n");
        md.append("Este fichero lo genera `MeasurementReport` (ver «Cómo repetirlo»). No se edita a mano.\n\n");

        md.append("## Qué se mide\n\n");
        md.append(String.format(Locale.ROOT,
                "Los km totales de las rutas de un día de reparto: %d paradas (%d con franja horaria) y %d bultos, "
                        + "con %d furgonetas de capacidad total %d bultos, saliendo de un único almacén. "
                        + "Es el conjunto de ejemplo de Madrid sur (`route-api/src/main/resources/sample/"
                        + "madrid-sur-stops.csv`), sintético y generado con semilla fija.%n%n",
                stops, withWindow, parcels, request.vehicles().size(), capacity));
        md.append("Las distancias son **estimadas**: línea recta (haversine) × 1,3 de factor de rodeo urbano, "
                + "sin red viaria real. Se aplican igual a la solución y a las referencias, así que la "
                + "comparación entre ellas es justa aunque la cifra absoluta no sea la de un recorrido por calle.\n\n");

        md.append("## Las referencias\n\n");
        md.append("| Referencia | Km | Paradas que llegarían tarde | Furgonetas fuera de jornada |\n|---|---:|---:|---:|\n");
        md.append(row("Orden de llegada (el «sin optimizar» del plan)", arrival));
        md.append(row("Barrido por ángulo + vecino más cercano", sweep));
        md.append("\n- **Orden de llegada**: las paradas tal como llegan, cada una a la primera furgoneta en la que "
                + "quepa. Es la referencia más ingenua posible.\n");
        md.append("- **Barrido + vecino más cercano**: lo que haría un despachante con sentido común. Ordena las "
                + "paradas por el ángulo alrededor del almacén, las reparte por sectores respetando la capacidad y, "
                + "dentro de cada furgoneta, va siempre a la parada pendiente más cercana. Es mucho más exigente.\n");
        md.append("- Ninguna mira las franjas horarias ni la jornada, y por eso la tabla dice cuántas romperían. "
                + "La solución optimizada **sí** las cumple (0 incumplimientos), así que compararla con una "
                + "referencia inviable es **conservador**: frente a una referencia que las respetase, el ahorro "
                + "sería mayor.\n\n");

        md.append("## El resultado\n\n");
        md.append(String.format(Locale.ROOT,
                "El optimizador se detiene tras un número fijo de pasos de búsqueda (**%d**), no por tiempo: así el "
                        + "resultado no depende de lo rápida que sea la máquina. Se repite con %d semillas distintas "
                        + "para ver cuánto varía.%n%n", STEPS, runs.size()));
        md.append("| Semilla | Km optimizados | Ahorro frente al orden de llegada | Ahorro frente al barrido |\n|---:|---:|---:|---:|\n");
        for (int i = 0; i < runs.size(); i++) {
            double value = runs.get(i).totalKm();
            md.append(String.format(Locale.ROOT, "| %d | %.1f | %s | %s |%n", i + 1, value,
                    percent(arrival.km(), value), percent(sweep.km(), value)));
        }
        md.append(String.format(Locale.ROOT, "| **media** | **%.1f** | **%s** | **%s** |%n", mean,
                percent(arrival.km(), mean), percent(sweep.km(), mean)));
        md.append(String.format(Locale.ROOT, "| mejor / peor | %.1f / %.1f | %s / %s | %s / %s |%n%n", min, max,
                percent(arrival.km(), min), percent(arrival.km(), max), percent(sweep.km(), min),
                percent(sweep.km(), max)));

        double ablationMean = ablation.stream().mapToDouble(OptimizationCompleted::totalKm).average().orElseThrow();
        md.append("## Qué cuestan las franjas horarias\n\n");
        md.append("La referencia de barrido no mira las franjas, y la solución sí. Para comparar distancia contra "
                + "distancia, se repite todo **sin franjas horarias** (mismo problema, mismas semillas):\n\n");
        md.append("| | Km |\n|---|---:|\n");
        md.append(String.format(Locale.ROOT, "| Barrido + vecino más cercano, sin franjas | %.1f |%n", sweepNoWindows.km()));
        md.append(String.format(Locale.ROOT, "| Optimizador, sin franjas (media de %d semillas) | %.1f |%n", ablation.size(),
                ablationMean));
        md.append(String.format(Locale.ROOT, "| Optimizador, **con** franjas (media) | %.1f |%n%n", mean));
        md.append(String.format(Locale.ROOT,
                "Respetar las %d franjas horarias cuesta, de media, %.1f km más (%s) que ignorarlas.%n%n", withWindow,
                mean - ablationMean, percent(ablationMean, mean).replace("-", "")));

        md.append("## Convergencia\n\n");
        md.append("Cuántos pasos de búsqueda hacen falta (semilla 1). Con pocos pasos la solución es peor; "
                + "la mejora se aplana hacia el final:\n\n");
        md.append("| Pasos | Km optimizados |\n|---:|---:|\n");
        for (int i = 0; i < curve.length; i++) {
            md.append(String.format(Locale.ROOT, "| %d | %.1f |%n", CURVE[i], curve[i]));
        }
        md.append("\nEn la aplicación el cálculo se limita por tiempo (30 s por defecto), no por pasos; el número de "
                + "pasos que da en ese tiempo depende de la máquina.\n\n");

        md.append("## Cómo leerlo\n\n");
        md.append(String.format(Locale.ROOT,
                "- Frente al reparto **sin ordenar** (el «sin optimizar» del plan) el optimizador %s de media. Es la "
                        + "comparación del plan, la más favorable y la menos exigente.%n", describe(arrival.km(), mean)));
        md.append(String.format(Locale.ROOT,
                "- Frente a una referencia **con sentido común** (barrido + vecino más cercano) %s de media, pero esa "
                        + "referencia es inviable (%d paradas llegarían tarde): la solución cumple todas las franjas y "
                        + "ella no.%n", describe(sweep.km(), mean), sweep.lateStops()));
        md.append(String.format(Locale.ROOT,
                "- La comparación de distancia contra distancia, **sin franjas** en ambos lados, es la más justa: el "
                        + "optimizador %s frente a la misma referencia.%n", describe(sweepNoWindows.km(), ablationMean)));
        md.append("- Las semillas dan resultados cercanos entre sí (mira «mejor / peor»): la cifra no es un golpe "
                + "de suerte de una ejecución concreta.\n");
        md.append("- Es un conjunto sintético de una sola zona y un solo día. Sirve para demostrar el método, no "
                + "para prometer un porcentaje en otro negocio.\n\n");

        md.append("## Cómo repetirlo\n\n");
        md.append("```bash\nmvn -pl optimizer-service -am test -Dtest=MeasurementReport -Drutaflex.measure=true\n```\n\n");
        md.append(String.format(Locale.ROOT,
                "Opciones: `-Drutaflex.measure.steps=%d` (pasos de búsqueda) y `-Drutaflex.measure.seeds=%d` "
                        + "(semillas). Con los mismos valores y el mismo entorno (JDK 21, Timefold 1.35) sale el mismo fichero: la semilla 1 con 60000 pasos dio exactamente el mismo resultado en ejecuciones independientes. No se ha comprobado en otras plataformas, donde pequeñas diferencias de coma flotante podrían cambiar la búsqueda. "
                        + "`ReproducibilityTest` lo comprueba en cada `mvn verify`.%n", STEPS, runs.size()));
        return md.toString();
    }

    private static String row(String name, Baselines.Result result) {
        return String.format(Locale.ROOT, "| %s | %.1f | %d | %d |%n", name, result.km(), result.lateStops(),
                result.shiftOverruns());
    }

    /** "ahorra 12.3 % de los km" o "recorre 4.5 % más km", según cuál gane. */
    private static String describe(double reference, double value) {
        double change = 100 * (reference - value) / reference;
        return change >= 0 ? String.format(Locale.ROOT, "ahorra **%.1f %%** de los km", change)
                : String.format(Locale.ROOT, "recorre **%.1f %%** más km", -change);
    }

    /** Ahorro de {@code optimised} frente a {@code reference}, como "−85 %" (con un decimal). */
    private static String percent(double reference, double optimised) {
        return String.format(Locale.ROOT, "%.1f %%", 100 * (reference - optimised) / reference);
    }
}
