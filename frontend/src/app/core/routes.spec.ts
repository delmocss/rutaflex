import {
  ROUTE_COLORS,
  buildMapRoutes,
  formatKm,
  formatSeconds,
  isActive,
  isStale,
  routeColor,
  savedKm,
  todayLocal,
  savedPercent,
} from './domain';
import { toLocation, toNumber } from './forms';
import { OptimizationJob, Stop, Vehicle } from './models';

const stop = (id: string, parcels = 1, window?: [string, string]): Stop => ({
  id,
  deliveryDate: '2026-12-14',
  address: `Calle ${id}`,
  location: { lat: 40.2, lon: -3.7 },
  parcels,
  windowStart: window?.[0],
  windowEnd: window?.[1],
  createdAt: '2026-12-01T10:00:00Z',
});

const van = (id: string, capacity = 60): Vehicle => ({
  id,
  warehouseId: 'w',
  name: `Furgoneta ${id}`,
  capacityParcels: capacity,
  shiftStart: '08:00:00',
  shiftEnd: '15:00:00',
});

const job = (routes: OptimizationJob['routes']): Pick<OptimizationJob, 'routes'> => ({ routes });
const depot = { location: { lat: 40.19, lon: -3.65 } };

describe('buildMapRoutes', () => {
  const routes = job([
    {
      vehicleId: 'a',
      distanceKm: 12.3,
      stops: [
        { stopId: 's1', sequence: 1, estimatedArrival: '09:05:00' },
        { stopId: 's2', sequence: 2, estimatedArrival: '09:40:00' },
      ],
    },
    {
      vehicleId: 'b',
      distanceKm: 5,
      stops: [{ stopId: 's3', sequence: 1, estimatedArrival: '10:00:00' }],
    },
  ]);
  const stops = [stop('s1', 2), stop('s2', 3, ['09:00:00', '11:00:00']), stop('s3', 4)];

  it('une el resultado con furgonetas y paradas: nombre, color, bultos, capacidad, hora y franja', () => {
    const built = buildMapRoutes(routes, [van('a', 40), van('b')], stops, depot);

    expect(built).toHaveLength(2);
    expect(built[0]).toMatchObject({
      id: 'a',
      name: 'Furgoneta a',
      color: ROUTE_COLORS[0],
      km: 12.3,
      parcels: 5,
      capacity: 40,
      depot: depot.location,
    });
    expect(built[0].stops.map((s) => [s.sequence, s.address, s.arrival, s.window])).toEqual([
      [1, 'Calle s1', '09:05', null],
      [2, 'Calle s2', '09:40', '09:00 - 11:00'],
    ]);
    expect(built[1].color).toBe(ROUTE_COLORS[1]);
  });

  it('omite lo que ya no existe en vez de romper (una parada borrada, una furgoneta desconocida)', () => {
    const built = buildMapRoutes(routes, [van('a')], [stop('s1', 2)], depot);

    expect(built[0].stops.map((s) => s.id)).toEqual(['s1']);
    expect(built[1].name).toBe('Furgoneta 2');
    expect(built[1].capacity).toBe(0);
    expect(built[1].stops).toEqual([]);
  });

  it('sin resultado, sin rutas o sin almacen no dibuja nada', () => {
    expect(buildMapRoutes(null, [], [], depot)).toEqual([]);
    expect(buildMapRoutes(job(undefined), [], [], depot)).toEqual([]);
    expect(buildMapRoutes(routes, [], stops, null)).toEqual([]);
  });

  it('los colores se reparten por orden y dan la vuelta si hay mas rutas que colores', () => {
    expect(routeColor(0)).toBe(ROUTE_COLORS[0]);
    expect(routeColor(ROUTE_COLORS.length)).toBe(ROUTE_COLORS[0]);
    expect(new Set(ROUTE_COLORS).size).toBe(ROUTE_COLORS.length);
  });
});

describe('isStale', () => {
  const routes = job([
    {
      vehicleId: 'a',
      distanceKm: 1,
      stops: [
        { stopId: 's1', sequence: 1, estimatedArrival: '09:00:00' },
        { stopId: 's2', sequence: 2, estimatedArrival: '09:10:00' },
      ],
    },
  ]);

  it('no esta desactualizado si cubre exactamente las paradas del dia', () => {
    expect(isStale(routes, [{ id: 's2' }, { id: 's1' }])).toBe(false);
  });

  it('lo esta si se anadio una parada', () => {
    expect(isStale(routes, [{ id: 's1' }, { id: 's2' }, { id: 's3' }])).toBe(true);
  });

  it('lo esta si se borro una', () => {
    expect(isStale(routes, [{ id: 's1' }])).toBe(true);
  });

  it('lo esta si se cambiaron por otras del mismo numero', () => {
    expect(isStale(routes, [{ id: 's1' }, { id: 'otra' }])).toBe(true);
  });

  it('sin rutas no hay nada que desactualizar', () => {
    expect(isStale(null, [{ id: 's1' }])).toBe(false);
    expect(isStale(job(undefined), [])).toBe(false);
  });
});

describe('ahorro y formatos', () => {
  it('calcula km y porcentaje de ahorro', () => {
    expect(savedKm({ totalKm: 124.3, baselineKm: 793.8 })).toBeCloseTo(669.5);
    expect(savedPercent({ totalKm: 124.3, baselineKm: 793.8 })).toBe(84);
  });

  it('sin totales no hay ahorro, y con linea base 0 es 0 (no una division por cero)', () => {
    expect(savedKm({})).toBeNull();
    expect(savedPercent({})).toBeNull();
    expect(savedPercent({ totalKm: 0, baselineKm: 0 })).toBe(0);
  });

  it('da formato a km y segundos en espanol', () => {
    expect(formatKm(124.26)).toBe('124,3 km');
    expect(formatKm(null)).toBe('—');
    expect(formatSeconds(8010)).toBe('8,0 s');
    expect(formatSeconds(undefined)).toBe('—');
  });

  it('un trabajo activo es el que esta en cola o calculando', () => {
    expect(isActive('QUEUED')).toBe(true);
    expect(isActive('RUNNING')).toBe(true);
    expect(isActive('COMPLETED')).toBe(false);
    expect(isActive('FAILED')).toBe(false);
  });
});

describe('formularios', () => {
  it.each([
    ['40.25', 40.25],
    ['40,25', 40.25],
    ['  -3.7 ', -3.7],
    ['-0', -0],
    ['7', 7],
  ])('toNumber(%s) = %s', (text, expected) => expect(toNumber(text)).toBe(expected));

  it.each([[''], ['  '], ['abc'], ['1.2.3'], ['1e5'], ['--1'], ['40.']])(
    'toNumber(%j) no es un numero',
    (text) => expect(toNumber(text)).toBeNull(),
  );

  it('acepta un numero ya numerico y rechaza NaN o infinito', () => {
    expect(toNumber(5)).toBe(5);
    expect(toNumber(NaN)).toBeNull();
    expect(toNumber(Infinity)).toBeNull();
  });

  it('toLocation devuelve las coordenadas o un mensaje', () => {
    expect(toLocation('40,2428', '-3.6997')).toEqual({ lat: 40.2428, lon: -3.6997 });
    expect(toLocation('95', '0')).toBe('La latitud debe ser un número entre −90 y 90.');
    expect(toLocation('', '0')).toBe('La latitud debe ser un número entre −90 y 90.');
    expect(toLocation('0', '181')).toBe('La longitud debe ser un número entre −180 y 180.');
    expect(toLocation('90', '-180')).toEqual({ lat: 90, lon: -180 });
  });
});

describe('todayLocal', () => {
  it('usa la fecha local, no la UTC: a las 00:30 de Espana sigue siendo hoy', () => {
    // 30 de noviembre, 00:30 hora local (en UTC aun seria el dia 29 por la noche segun la zona).
    expect(todayLocal(new Date(2026, 10, 30, 0, 30))).toBe('2026-11-30');
    expect(todayLocal(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
  });

  it('rellena con ceros mes y dia', () => {
    expect(todayLocal(new Date(2027, 2, 4, 12, 0))).toBe('2027-03-04');
  });
});
