package com.rutaflex.optimizer;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationCompleted.RouteStop;
import com.rutaflex.events.OptimizationCompleted.VehicleRoute;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RoutePlan;
import com.rutaflex.optimizer.solver.RouteSolver;
import com.rutaflex.optimizer.solver.RouteVehicle;
import com.rutaflex.optimizer.solver.Visit;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Convierte el evento de petición en un problema de Timefold, lo resuelve y devuelve el evento de resultado
 * con la comparación contra la línea base. No sabe nada de Kafka: recibe y devuelve registros.
 */
public class RouteOptimizer {

    private final RouteSolver solver;
    private final Assumptions assumptions;

    public RouteOptimizer(RouteSolver solver, Assumptions assumptions) {
        this.solver = solver;
        this.assumptions = assumptions;
    }

    public OptimizationCompleted optimize(OptimizationRequested request) {
        validate(request);
        long started = System.nanoTime();

        RoutePlan problem = toPlan(request);
        double baselineKm = baselineKm(problem);
        RoutePlan solution = solver.solve(problem);

        if (solution.getScore().hardScore() < 0) {
            throw new OptimizationException("No feasible plan found (hard score " + solution.getScore().hardScore()
                    + "): capacity, time windows or the end of the shift cannot be met with these vehicles");
        }

        List<VehicleRoute> routes = new ArrayList<>();
        double totalKm = 0;
        for (RouteVehicle vehicle : solution.getVehicles()) {
            if (vehicle.getVisits().isEmpty()) {
                continue;
            }
            double km = routeKm(vehicle, vehicle.getVisits());
            totalKm += km;
            List<RouteStop> stops = new ArrayList<>();
            int sequence = 1;
            for (Visit visit : vehicle.getVisits()) {
                stops.add(new RouteStop(visit.getId(), sequence++, LocalTime.ofSecondOfDay(visit.serviceStart())));
            }
            routes.add(new VehicleRoute(vehicle.getId(), round(km), stops));
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        return new OptimizationCompleted(request.jobId(), routes, round(totalKm), round(baselineKm), millis);
    }

    /**
     * Reparto sin optimizar: las paradas en el orden en que llegaron, cada una a la primera furgoneta en la
     * que quepa. Es la línea base contra la que se mide el ahorro. No tiene en cuenta franjas horarias, así
     * que es una referencia de distancia, no necesariamente un reparto viable.
     */
    double baselineKm(RoutePlan problem) {
        List<RouteVehicle> vans = problem.getVehicles();
        List<List<Visit>> loads = new ArrayList<>();
        int[] used = new int[vans.size()];
        for (int i = 0; i < vans.size(); i++) {
            loads.add(new ArrayList<>());
        }
        for (Visit visit : problem.getVisits()) {
            boolean placed = false;
            for (int i = 0; i < vans.size() && !placed; i++) {
                if (used[i] + visit.getParcels() <= vans.get(i).getCapacityParcels()) {
                    loads.get(i).add(visit);
                    used[i] += visit.getParcels();
                    placed = true;
                }
            }
            if (!placed) {
                throw new OptimizationException("Stop '" + visit.getAddress()
                        + "' does not fit in any vehicle when loading in arrival order");
            }
        }
        double km = 0;
        for (int i = 0; i < vans.size(); i++) {
            km += routeKm(vans.get(i), loads.get(i));
        }
        return km;
    }

    /** Km de una ruta almacén → paradas en orden → almacén. */
    private static double routeKm(RouteVehicle vehicle, List<Visit> visits) {
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

    private RoutePlan toPlan(OptimizationRequested request) {
        var depot = request.warehouse().location();
        List<RouteVehicle> vehicles = request.vehicles().stream()
                .map(v -> new RouteVehicle(v.id(), v.name(), v.capacityParcels(), seconds(v.shiftStart()),
                        seconds(v.shiftEnd()), depot.lat(), depot.lon(), assumptions))
                .toList();
        List<Visit> visits = request.stops().stream()
                .map(s -> new Visit(s.id(), s.address(), s.location().lat(), s.location().lon(), s.parcels(),
                        s.windowStart() == null ? null : seconds(s.windowStart()),
                        s.windowEnd() == null ? null : seconds(s.windowEnd()), assumptions))
                .toList();
        return new RoutePlan(vehicles, visits);
    }

    private static void validate(OptimizationRequested request) {
        if (request.vehicles() == null || request.vehicles().isEmpty()) {
            throw new OptimizationException("There are no vehicles to plan with");
        }
        if (request.stops() == null || request.stops().isEmpty()) {
            throw new OptimizationException("There are no stops to plan");
        }
        int capacity = request.vehicles().stream().mapToInt(OptimizationRequested.Vehicle::capacityParcels).sum();
        int parcels = request.stops().stream().mapToInt(OptimizationRequested.Stop::parcels).sum();
        if (parcels > capacity) {
            throw new OptimizationException("There are " + parcels + " parcels but the vehicles only carry "
                    + capacity);
        }
        int largest = request.vehicles().stream().mapToInt(OptimizationRequested.Vehicle::capacityParcels)
                .max().orElse(0);
        request.stops().stream().filter(s -> s.parcels() > largest).findFirst().ifPresent(s -> {
            throw new OptimizationException("Stop '" + s.address() + "' has " + s.parcels()
                    + " parcels, more than any single vehicle carries (" + largest + ")");
        });
    }

    private static int seconds(LocalTime time) {
        return time.toSecondOfDay();
    }

    private static double round(double km) {
        return Math.round(km * 100.0) / 100.0;
    }
}
