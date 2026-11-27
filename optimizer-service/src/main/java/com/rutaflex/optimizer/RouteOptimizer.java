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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        double baselineKm = Baselines.arrivalOrder(problem).km();
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
            double km = Baselines.routeKm(vehicle, vehicle.getVisits());
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

    RoutePlan toPlan(OptimizationRequested request) {
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
        if (request.warehouse() == null || request.warehouse().location() == null) {
            throw new OptimizationException("The warehouse and its location are required");
        }
        if (request.vehicles() == null || request.vehicles().isEmpty()) {
            throw new OptimizationException("There are no vehicles to plan with");
        }
        if (request.stops() == null || request.stops().isEmpty()) {
            throw new OptimizationException("There are no stops to plan");
        }
        Set<UUID> ids = new HashSet<>();
        for (var v : request.vehicles()) {
            if (v == null || v.id() == null || !ids.add(v.id())) {
                throw new OptimizationException("Vehicle ids are required and must be unique");
            }
            if (v.capacityParcels() <= 0) {
                throw new OptimizationException("Vehicle '" + v.name() + "' must have a positive capacity");
            }
            if (v.shiftStart() == null || v.shiftEnd() == null || !v.shiftEnd().isAfter(v.shiftStart())) {
                throw new OptimizationException("Vehicle '" + v.name() + "' needs a shift that ends after it starts "
                        + "(shifts crossing midnight are not supported)");
            }
        }
        ids.clear();
        for (var stop : request.stops()) {
            if (stop == null || stop.id() == null || !ids.add(stop.id())) {
                throw new OptimizationException("Stop ids are required and must be unique");
            }
            if (stop.location() == null) {
                throw new OptimizationException("Stop '" + stop.address() + "' has no location");
            }
            if (stop.parcels() <= 0) {
                throw new OptimizationException("Stop '" + stop.address() + "' must have at least one parcel");
            }
            if ((stop.windowStart() == null) != (stop.windowEnd() == null)) {
                throw new OptimizationException("Stop '" + stop.address()
                        + "' must have both ends of its time window or none");
            }
            if (stop.windowStart() != null && stop.windowStart().isAfter(stop.windowEnd())) {
                throw new OptimizationException("Stop '" + stop.address() + "' has a time window that ends before it starts");
            }
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
