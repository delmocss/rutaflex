import { Stop, StopDay, Vehicle } from './models';

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
