import { GeoPoint } from './models';

/** "40,25" o " 40.25 " -> 40.25. Vacio o no numerico -> null (la API exige punto decimal; aqui se admite la coma). */
export function toNumber(text: string | number | null | undefined): number | null {
  if (typeof text === 'number') {
    return Number.isFinite(text) ? text : null;
  }
  const clean = (text ?? '').trim().replace(',', '.');
  if (clean === '' || !/^-?\d+(\.\d+)?$/.test(clean)) {
    return null;
  }
  return Number(clean);
}

/** Coordenadas validas o un mensaje de error en espanol. */
export function toLocation(lat: string, lon: string): GeoPoint | string {
  const latitude = toNumber(lat);
  const longitude = toNumber(lon);
  if (latitude === null || latitude < -90 || latitude > 90) {
    return 'La latitud debe ser un número entre −90 y 90.';
  }
  if (longitude === null || longitude < -180 || longitude > 180) {
    return 'La longitud debe ser un número entre −180 y 180.';
  }
  return { lat: latitude, lon: longitude };
}
