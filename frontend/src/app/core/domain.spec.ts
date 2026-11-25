import { dayOptionLabel, formatDay, formatTime, hasWindow, summarize, windowLabel } from './domain';
import { Stop, Vehicle } from './models';

const stop = (overrides: Partial<Stop> = {}): Stop => ({
  id: 's',
  deliveryDate: '2026-12-14',
  address: 'Calle Mayor 1',
  location: { lat: 40.2, lon: -3.7 },
  parcels: 1,
  createdAt: '2026-12-01T10:00:00Z',
  ...overrides,
});

const van = (capacityParcels: number): Vehicle => ({
  id: 'v',
  warehouseId: 'w',
  name: 'Furgoneta',
  capacityParcels,
  shiftStart: '08:00:00',
  shiftEnd: '15:00:00',
});

describe('formatTime', () => {
  it('quita los segundos', () => expect(formatTime('09:00:00')).toBe('09:00'));
  it('acepta HH:mm', () => expect(formatTime('09:30')).toBe('09:30'));
  it('devuelve vacio si no hay hora', () => {
    expect(formatTime(null)).toBe('');
    expect(formatTime(undefined)).toBe('');
  });
});

describe('franja horaria', () => {
  it('la franja completa se muestra como rango', () => {
    const withWindow = stop({ windowStart: '09:00:00', windowEnd: '11:00:00' });
    expect(hasWindow(withWindow)).toBe(true);
    expect(windowLabel(withWindow)).toBe('09:00 - 11:00');
  });

  it('sin franja, o a medias, la parada es flexible', () => {
    expect(windowLabel(stop())).toBeNull();
    expect(windowLabel(stop({ windowStart: null, windowEnd: null }))).toBeNull();
    expect(hasWindow(stop({ windowStart: '09:00:00', windowEnd: null }))).toBe(false);
  });
});

describe('summarize', () => {
  it('suma bultos y capacidad y calcula la ocupacion', () => {
    const summary = summarize(
      [stop({ parcels: 3 }), stop({ parcels: 2, windowStart: '09:00:00', windowEnd: '11:00:00' })],
      [van(60), van(40)],
    );
    expect(summary).toEqual({
      stops: 2,
      parcels: 5,
      withWindow: 1,
      vehicles: 2,
      capacity: 100,
      utilization: 5,
      overCapacity: false,
    });
  });

  it('avisa cuando hay mas bultos que capacidad', () => {
    const summary = summarize([stop({ parcels: 70 })], [van(60)]);
    expect(summary.utilization).toBe(117);
    expect(summary.overCapacity).toBe(true);
  });

  it('sin flota no hay capacidad ni se divide por cero', () => {
    const summary = summarize([stop({ parcels: 4 })], []);
    expect(summary.capacity).toBe(0);
    expect(summary.utilization).toBe(0);
    expect(summary.overCapacity).toBe(false);
  });

  it('un dia vacio da todo a cero', () => {
    expect(summarize([], [van(60)])).toMatchObject({ stops: 0, parcels: 0, utilization: 0 });
  });
});

describe('fechas', () => {
  it('muestra el dia correcto, sin retrasarlo por la zona horaria', () => {
    // new Date('2026-12-14') es medianoche UTC: al oeste de Greenwich seria el dia 13.
    expect(formatDay('2026-12-14')).toContain('14');
    expect(formatDay('2026-12-14')).toContain('2026');
  });

  it('describe una opcion del selector con singular y plural', () => {
    expect(dayOptionLabel({ date: '2026-12-14', stops: 80, parcels: 205 })).toContain(
      '80 paradas · 205 bultos',
    );
    expect(dayOptionLabel({ date: '2026-12-14', stops: 1, parcels: 3 })).toContain(
      '1 parada · 3 bultos',
    );
  });
});
