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
