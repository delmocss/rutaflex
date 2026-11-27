package com.rutaflex.optimizer;

import com.rutaflex.optimizer.solver.RoutePlan;
import com.rutaflex.optimizer.solver.RouteVehicle;
import com.rutaflex.optimizer.solver.Visit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Referencias contra las que se mide el ahorro del optimizador. Ninguna usa aleatoriedad: con la misma entrada
 * dan siempre el mismo resultado.
 * <ul>
 *   <li><b>Orden de llegada</b>: las paradas tal como llegan, cada una a la primera furgoneta en la que quepa.
 *       Es el "reparto sin optimizar" del plan y la referencia más ingenua posible.</li>
 *   <li><b>Barrido + vecino más cercano</b>: lo que haría un despachante con sentido común o un script sencillo.
 *       Ordena las paradas por el ángulo alrededor del almacén, las reparte por sectores respetando la capacidad
 *       y dentro de cada furgoneta va siempre a la parada más cercana. Es una referencia mucho más exigente.</li>
 * </ul>
 * Ninguna mira las franjas horarias, así que pueden ser inviables; por eso cada resultado informa de cuántas
 * paradas llegarían tarde y cuántas furgonetas se pasarían de jornada. Como la solución optimizada sí las
 * cumple, comparar sus km con los de una referencia inviable es conservador: el ahorro frente a una referencia
 * que las respetase sería mayor.
 */
final class Baselines {

    /**
     * @param lateStops      paradas a las que se llegaría después de que cierre su franja
     * @param shiftOverruns  furgonetas que volverían después de que acabe su jornada
     * @param overloadedVans furgonetas con más bultos que su capacidad (solo pasa si no cabe todo en orden y el
     *                       sobrante se manda a la más vacía)
     * @param routes         las paradas de cada furgoneta, en orden de visita
     */
    record Result(double km, int lateStops, int shiftOverruns, int overloadedVans, List<List<Visit>> routes) {
    }

    private Baselines() {
    }

    static Result arrivalOrder(RoutePlan problem) {
        List<RouteVehicle> vans = problem.getVehicles();
        List<List<Visit>> loads = emptyLoads(vans.size());
        int[] used = new int[vans.size()];
        for (Visit visit : problem.getVisits()) {
            int target = firstFit(vans, used, visit);
            if (target < 0) {
                target = emptiest(vans, used);
            }
            loads.get(target).add(visit);
            used[target] += visit.getParcels();
        }
        return evaluate(vans, loads);
    }

    /** El barrido empieza por el sur (corte en -π): es el corte «de serie». */
    static Result sweepNearest(RoutePlan problem) {
        return sweepNearest(problem, -Math.PI);
    }

    /**
     * El resultado del barrido depende de dónde se corta el círculo: las paradas se ordenan por el ángulo
     * alrededor del almacén a partir de {@code startAngle}, y las dos paradas que quedan a ambos lados del corte
     * acaban en furgonetas distintas aunque estén juntas.
     */
    static Result sweepNearest(RoutePlan problem, double startAngle) {
        List<RouteVehicle> vans = problem.getVehicles();
        RouteVehicle any = vans.get(0);
        double depotLat = any.getDepotLat();
        double depotLon = any.getDepotLon();

        // Barrido: por el angulo alrededor del almacen (desempate por id, para que sea determinista).
        List<Visit> sorted = new ArrayList<>(problem.getVisits());
        sorted.sort(Comparator
                .comparingDouble((Visit v) -> fromStart(
                        Math.atan2(v.getLon() - depotLon, v.getLat() - depotLat), startAngle))
                .thenComparing(v -> v.getId().toString()));

        // Cada furgoneta toma un sector contiguo hasta llenarse.
        List<List<Visit>> loads = emptyLoads(vans.size());
        int[] used = new int[vans.size()];
        int current = 0;
        for (Visit visit : sorted) {
            while (current < vans.size() - 1
                    && used[current] + visit.getParcels() > vans.get(current).getCapacityParcels()) {
                current++;
            }
            int target = used[current] + visit.getParcels() <= vans.get(current).getCapacityParcels()
                    ? current : emptiest(vans, used);
            loads.get(target).add(visit);
            used[target] += visit.getParcels();
        }

        // Dentro de cada furgoneta: siempre a la parada pendiente mas cercana.
        List<List<Visit>> ordered = new ArrayList<>();
        for (int i = 0; i < vans.size(); i++) {
            ordered.add(nearestNeighbour(vans.get(i), loads.get(i)));
        }
        return evaluate(vans, ordered);
    }

    /**
     * Km del barrido probando {@code rotations} cortes distintos del círculo, equiespaciados. Sirve para ver cuánto
     * depende la referencia de dónde se corta: el corte de serie puede ser, por casualidad, bueno o malo.
     */
    static java.util.DoubleSummaryStatistics sweepRange(RoutePlan problem, int rotations) {
        java.util.DoubleSummaryStatistics stats = new java.util.DoubleSummaryStatistics();
        for (int i = 0; i < rotations; i++) {
            stats.accept(sweepNearest(problem, -Math.PI + 2 * Math.PI * i / rotations).km());
        }
        return stats;
    }

    /** Ángulo medido desde {@code startAngle}, en [0, 2π). */
    private static double fromStart(double angle, double startAngle) {
        double twoPi = 2 * Math.PI;
        double shifted = (angle - startAngle) % twoPi;
        return shifted < 0 ? shifted + twoPi : shifted;
    }

    /** Km de una ruta almacén → paradas en orden → almacén. */
    static double routeKm(RouteVehicle vehicle, List<Visit> visits) {
        if (visits.isEmpty()) {
            return 0;
        }
        long meters = vehicle.distanceDepot(visits.get(0));
        for (int i = 1; i < visits.size(); i++) {
            meters += vehicle.distance(visits.get(i - 1), visits.get(i));
        }
        meters += vehicle.distanceDepot(visits.get(visits.size() - 1));
        return meters / 1000.0;
    }

    private static List<Visit> nearestNeighbour(RouteVehicle van, List<Visit> stops) {
        List<Visit> pending = new ArrayList<>(stops);
        List<Visit> route = new ArrayList<>();
        Visit last = null;
        while (!pending.isEmpty()) {
            Visit best = null;
            int bestMeters = Integer.MAX_VALUE;
            for (Visit candidate : pending) {
                int meters = last == null ? van.distanceDepot(candidate) : van.distance(last, candidate);
                if (meters < bestMeters) {
                    bestMeters = meters;
                    best = candidate;
                }
            }
            route.add(best);
            pending.remove(best);
            last = best;
        }
        return route;
    }

    /** Km totales y cuántas franjas y jornadas se incumplirían, simulando la ruta sin tocar el problema. */
    private static Result evaluate(List<RouteVehicle> vans, List<List<Visit>> loads) {
        double km = 0;
        int late = 0;
        int overruns = 0;
        int overloaded = 0;
        for (int i = 0; i < vans.size(); i++) {
            RouteVehicle van = vans.get(i);
            List<Visit> route = loads.get(i);
            if (route.isEmpty()) {
                continue;
            }
            if (route.stream().mapToInt(Visit::getParcels).sum() > van.getCapacityParcels()) {
                overloaded++;
            }
            km += routeKm(van, route);
            int clock = van.getShiftStart();
            Visit previous = null;
            for (Visit visit : route) {
                int arrival = clock + (previous == null
                        ? van.travelSecondsFromDepot(visit) : van.travelSeconds(previous, visit));
                if (visit.hasWindow() && arrival > visit.getWindowEnd()) {
                    late++;
                }
                int start = visit.hasWindow() ? Math.max(arrival, visit.getWindowStart()) : arrival;
                clock = start + visit.getServiceSeconds();
                previous = visit;
            }
            if (clock + van.travelSecondsFromDepot(previous) > van.getShiftEnd()) {
                overruns++;
            }
        }
        return new Result(km, late, overruns, overloaded, loads);
    }

    private static List<List<Visit>> emptyLoads(int vans) {
        List<List<Visit>> loads = new ArrayList<>();
        for (int i = 0; i < vans; i++) {
            loads.add(new ArrayList<>());
        }
        return loads;
    }

    private static int firstFit(List<RouteVehicle> vans, int[] used, Visit visit) {
        for (int i = 0; i < vans.size(); i++) {
            if (used[i] + visit.getParcels() <= vans.get(i).getCapacityParcels()) {
                return i;
            }
        }
        return -1;
    }

    private static int emptiest(List<RouteVehicle> vans, int[] used) {
        int target = 0;
        for (int i = 1; i < vans.size(); i++) {
            if (vans.get(i).getCapacityParcels() - used[i] > vans.get(target).getCapacityParcels() - used[target]) {
                target = i;
            }
        }
        return target;
    }
}
