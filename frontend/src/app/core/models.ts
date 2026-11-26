/** Tipos del contrato de la API (route-api). */

export interface GeoPoint {
  lat: number;
  lon: number;
}

export interface Warehouse {
  id: string;
  name: string;
  address: string;
  location: GeoPoint;
  createdAt: string;
}

export interface Vehicle {
  id: string;
  warehouseId: string;
  name: string;
  capacityParcels: number;
  /** Hora de salida, "HH:mm:ss". */
  shiftStart: string;
  /** Hora limite de regreso, "HH:mm:ss". */
  shiftEnd: string;
}

export interface Stop {
  id: string;
  deliveryDate: string;
  address: string;
  location: GeoPoint;
  parcels: number;
  /** La franja va completa o no va: ambas informadas o ambas ausentes. */
  windowStart?: string | null;
  windowEnd?: string | null;
  createdAt: string;
}

/** Un dia con paradas y sus totales (selector de fecha). */
export interface StopDay {
  date: string;
  stops: number;
  parcels: number;
}

export interface ImportResult {
  date: string;
  imported: number;
  replaced: number;
}

// ---- optimizaciones ----

export type JobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface RouteStop {
  stopId: string;
  /** Empieza en 1. */
  sequence: number;
  /** Hora estimada de inicio de la atencion, "HH:mm:ss" (si llega antes de que abra la franja, espera). */
  estimatedArrival: string;
}

export interface VehicleRoute {
  vehicleId: string;
  distanceKm: number;
  stops: RouteStop[];
}

/** Un trabajo de optimizacion. Los listados no traen `routes` ni `computeMillis`. */
export interface OptimizationJob {
  id: string;
  warehouseId: string;
  deliveryDate: string;
  status: JobStatus;
  failureReason?: string;
  totalKm?: number;
  baselineKm?: number;
  requestedAt: string;
  startedAt?: string;
  finishedAt?: string;
  computeMillis?: number;
  routes?: VehicleRoute[];
}

// ---- cuerpos de escritura ----

export interface WarehouseInput {
  name: string;
  address: string;
  location: GeoPoint;
}

export interface VehicleInput {
  name: string;
  capacityParcels: number;
  shiftStart: string;
  shiftEnd: string;
}

export interface StopInput {
  deliveryDate: string;
  address: string;
  location: GeoPoint;
  parcels: number;
  windowStart?: string | null;
  windowEnd?: string | null;
}
