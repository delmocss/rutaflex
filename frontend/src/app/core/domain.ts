import { GeoPoint, JobStatus, OptimizationJob, Stop, StopDay, Vehicle, Warehouse } from './models';

/** "09:00:00" -> "09:00". */
export function formatTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '';
}

export function hasWindow(stop: Pick<Stop, 'windowStart' | 'windowEnd'>): boolean {
  return !!stop.windowStart && !!stop.windowEnd;
}

/** "09:00 - 11:00", o null si la parada es flexible. */
export function windowLabel(stop: Pick<Stop, 'windowStart' | 'windowEnd'>): string | null {
  return hasWindow(stop) ? `${formatTime(stop.windowStart)} - ${formatTime(stop.windowEnd)}` : null;
}

export interface PlanSummary {
  stops: number;
  parcels: number;
  withWindow: number;
  vehicles: number;
  capacity: number;
  /** Bultos / capacidad total, en porcentaje entero. Sin flota no hay capacidad: 0. */
  utilization: number;
  /** Mas bultos que capacidad: el problema no tiene solucion. */
  overCapacity: boolean;
}

export function summarize(stops: readonly Stop[], vehicles: readonly Vehicle[]): PlanSummary {
  const parcels = stops.reduce((sum, stop) => sum + stop.parcels, 0);
  const capacity = vehicles.reduce((sum, vehicle) => sum + vehicle.capacityParcels, 0);
  return {
    stops: stops.length,
    parcels,
    withWindow: stops.filter(hasWindow).length,
    vehicles: vehicles.length,
    capacity,
    utilization: capacity > 0 ? Math.round((parcels / capacity) * 100) : 0,
    overCapacity: capacity > 0 && parcels > capacity,
  };
}

const longDate = new Intl.DateTimeFormat('es-ES', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
});

/** "2026-12-14" -> "lun, 14 dic 2026". Se interpreta como fecha local: new Date("2026-12-14") es UTC y retrasa un dia al oeste de Greenwich. */
export function formatDay(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  return longDate.format(new Date(year, month - 1, day));
}

/** "lun, 14 dic 2026 · 80 paradas · 205 bultos" para el selector de dia. */
export function dayOptionLabel(day: StopDay): string {
  return `${formatDay(day.date)} · ${day.stops} ${day.stops === 1 ? 'parada' : 'paradas'} · ${day.parcels} bultos`;
}

// ---- optimizaciones ----

/** Colores apagados y distinguibles entre si para las rutas (sin rojos vivos). Se reparten por orden de ruta. */
export const ROUTE_COLORS = [
  '#5f6f3a',
  '#3f6b8a',
  '#b3822f',
  '#7a4f7d',
  '#2f7f79',
  '#8a6a4a',
  '#6b7f8c',
  '#4f7f5a',
] as const;

export function routeColor(index: number): string {
  return ROUTE_COLORS[index % ROUTE_COLORS.length];
}

export const STATUS_LABEL: Record<JobStatus, string> = {
  QUEUED: 'En cola',
  RUNNING: 'Calculando',
  COMPLETED: 'Completada',
  FAILED: 'Fallida',
};

/** Pendiente de resultado: hay que seguir consultando. */
export function isActive(status: JobStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

const km1 = new Intl.NumberFormat('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** 124.26 -> "124,3 km". */
export function formatKm(km: number | null | undefined): string {
  return km == null ? '—' : `${km1.format(km)} km`;
}

/** 8010 -> "8,0 s". */
export function formatSeconds(millis: number | null | undefined): string {
  return millis == null ? '—' : `${km1.format(millis / 1000)} s`;
}

/** Km que se ahorran frente a la linea base; null si falta alguno de los dos totales. */
export function savedKm(job: Pick<OptimizationJob, 'totalKm' | 'baselineKm'>): number | null {
  return job.totalKm == null || job.baselineKm == null ? null : job.baselineKm - job.totalKm;
}

/** Ahorro en porcentaje entero; 0 si la linea base es 0. */
export function savedPercent(job: Pick<OptimizationJob, 'totalKm' | 'baselineKm'>): number | null {
  const saved = savedKm(job);
  if (saved == null || !job.baselineKm) {
    return saved == null ? null : 0;
  }
  return Math.round((saved / job.baselineKm) * 100);
}

/** Una ruta lista para dibujar y para listar. */
export interface MapRoute {
  id: string;
  name: string;
  color: string;
  km: number;
  parcels: number;
  capacity: number;
  depot: GeoPoint;
  stops: {
    id: string;
    sequence: number;
    address: string;
    location: GeoPoint;
    arrival: string;
    parcels: number;
    /** "09:00 - 11:00", o null si la parada es flexible. */
    window: string | null;
  }[];
}

/**
 * Une el resultado (que solo lleva ids) con los datos para poder dibujarlo. Lo que ya no exista (una parada
 * borrada despues de optimizar) se omite en vez de romper la pantalla.
 */
export function buildMapRoutes(
  job: Pick<OptimizationJob, 'routes'> | null,
  vehicles: readonly Vehicle[],
  stops: readonly Stop[],
  warehouse: Pick<Warehouse, 'location'> | null,
): MapRoute[] {
  if (!job?.routes || !warehouse) {
    return [];
  }
  const stopById = new Map(stops.map((stop) => [stop.id, stop]));
  const vehicleById = new Map(vehicles.map((vehicle) => [vehicle.id, vehicle]));

  return job.routes.map((route, index) => {
    const vehicle = vehicleById.get(route.vehicleId);
    const routeStops = route.stops.flatMap((item) => {
      const stop = stopById.get(item.stopId);
      return stop
        ? [
            {
              id: stop.id,
              sequence: item.sequence,
              address: stop.address,
              location: stop.location,
              arrival: formatTime(item.estimatedArrival),
              parcels: stop.parcels,
              window: windowLabel(stop),
            },
          ]
        : [];
    });
    return {
      id: route.vehicleId,
      name: vehicle?.name ?? `Furgoneta ${index + 1}`,
      color: routeColor(index),
      km: route.distanceKm,
      parcels: routeStops.reduce((sum, stop) => sum + stop.parcels, 0),
      capacity: vehicle?.capacityParcels ?? 0,
      depot: warehouse.location,
      stops: routeStops,
    };
  });
}

/**
 * Un resultado esta desactualizado si ya no cubre exactamente las paradas del dia (se importaron, borraron o
 * anadieron paradas despues de calcularlo): sus rutas ya no representan el reparto real.
 */
export function isStale(
  job: Pick<OptimizationJob, 'routes'> | null,
  stops: readonly Pick<Stop, 'id'>[],
): boolean {
  if (!job?.routes) {
    return false;
  }
  const planned = new Set(job.routes.flatMap((route) => route.stops.map((stop) => stop.stopId)));
  return planned.size !== stops.length || stops.some((stop) => !planned.has(stop.id));
}

const dateTime = new Intl.DateTimeFormat('es-ES', { dateStyle: 'short', timeStyle: 'short' });

/** "2026-11-26T17:29:39Z" -> "26/11/26, 18:29" (hora local). */
export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '—';
}

/** La fecha de hoy en la zona horaria local, "AAAA-MM-DD". (toISOString da la fecha UTC: de madrugada en Espana seria ayer.) */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
