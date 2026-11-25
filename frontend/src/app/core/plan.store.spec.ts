import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PlanStore } from './plan.store';
import { Stop, StopDay, Vehicle, Warehouse } from './models';

const warehouse: Warehouse = {
  id: 'w1',
  name: 'Almacen',
  address: 'Valdemoro',
  location: { lat: 40.19, lon: -3.65 },
  createdAt: '2026-12-01T10:00:00Z',
};
const vehicles: Vehicle[] = [
  {
    id: 'v1',
    warehouseId: 'w1',
    name: 'F1',
    capacityParcels: 60,
    shiftStart: '08:00:00',
    shiftEnd: '15:00:00',
  },
];
const days: StopDay[] = [
  { date: '2026-12-14', stops: 2, parcels: 5 },
  { date: '2026-12-15', stops: 1, parcels: 3 },
];
const stop = (id: string, parcels: number): Stop => ({
  id,
  deliveryDate: '2026-12-15',
  address: id,
  location: { lat: 40.2, lon: -3.7 },
  parcels,
  createdAt: '2026-12-01T10:00:00Z',
});

describe('PlanStore', () => {
  let store: PlanStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(PlanStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Responde a las peticiones que hace load() para que la promesa avance. */
  async function flushLoad(
    options: { warehouses?: Warehouse[]; days?: StopDay[]; stops?: Stop[] } = {},
  ) {
    const warehouses = options.warehouses ?? [warehouse];
    const stopDays = options.days ?? days;
    await Promise.resolve();
    http.expectOne('/api/warehouses').flush(warehouses);
    http.expectOne('/api/stops/dates').flush(stopDays);
    if (warehouses.length > 0) {
      await new Promise((resolve) => setTimeout(resolve));
      http.expectOne('/api/warehouses/w1/vehicles').flush(vehicles);
    }
    if (stopDays.length > 0) {
      await new Promise((resolve) => setTimeout(resolve));
      const request = http.expectOne((r) => r.url === '/api/stops' && r.params.has('date'));
      request.flush(options.stops ?? [stop('a', 3)]);
    }
  }

  it('al cargar elige el dia mas reciente y deja las paradas y el resumen listos', async () => {
    const loading = store.load();
    await flushLoad();
    await loading;

    expect(store.warehouse()?.id).toBe('w1');
    expect(store.vehicles()).toHaveLength(1);
    expect(store.selectedDate()).toBe('2026-12-15');
    expect(store.stops()).toHaveLength(1);
    expect(store.summary()).toMatchObject({ stops: 1, parcels: 3, capacity: 60 });
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
    expect(store.isEmpty()).toBe(false);
  });

  it('sin datos queda vacio (para mostrar como cargarlos), no en error', async () => {
    const loading = store.load();
    await flushLoad({ warehouses: [], days: [] });
    await loading;

    expect(store.isEmpty()).toBe(true);
    expect(store.error()).toBeNull();
    expect(store.stops()).toEqual([]);
  });

  it('si la API falla guarda un error mostrable y deja de cargar', async () => {
    const loading = store.load();
    await Promise.resolve();
    http.expectOne('/api/warehouses').error(new ProgressEvent('error'), { status: 0 });
    http.expectOne('/api/stops/dates').error(new ProgressEvent('error'), { status: 0 });
    await loading;

    expect(store.error()?.message).toContain('No se puede conectar');
    expect(store.loading()).toBe(false);
    expect(store.isEmpty()).toBe(false);
  });

  it('cambiar de dia carga sus paradas y quita la seleccion anterior', async () => {
    const loading = store.load();
    await flushLoad();
    await loading;
    store.selectStop('a');

    const selecting = store.selectDate('2026-12-14');
    await Promise.resolve();
    const request = http.expectOne(
      (r) => r.url === '/api/stops' && r.params.get('date') === '2026-12-14',
    );
    request.flush([stop('x', 2), stop('y', 3)]);
    await selecting;

    expect(store.selectedDate()).toBe('2026-12-14');
    expect(store.stops()).toHaveLength(2);
    expect(store.selectedStopId()).toBeNull();
  });

  it('importar envia el CSV con el dia y replace, recarga y deja elegido el dia importado', async () => {
    const file = new File(['address,lat,lon,parcels\n'], 'stops.csv', { type: 'text/csv' });

    const importing = store.importCsv('2026-12-14', file, true);
    await Promise.resolve();
    const upload = http.expectOne((r) => r.url === '/api/stops/import');
    expect(upload.request.method).toBe('POST');
    expect(upload.request.params.get('date')).toBe('2026-12-14');
    expect(upload.request.params.get('replace')).toBe('true');
    expect(upload.request.body).toBeInstanceOf(FormData);
    upload.flush({ date: '2026-12-14', imported: 2, replaced: 2 });

    await new Promise((resolve) => setTimeout(resolve));
    await flushLoad({ stops: [stop('a', 1)] });
    const result = await importing;

    expect(result.imported).toBe(2);
    expect(store.selectedDate()).toBe('2026-12-14');
  });

  it('un CSV rechazado lanza el error de la API para que el panel lo muestre y no recarga', async () => {
    const file = new File(['x'], 'bad.csv');

    const importing = store.importCsv('2026-12-14', file, false);
    const outcome = importing.then(
      () => 'ok',
      (error: unknown) => error,
    );
    await Promise.resolve();
    http
      .expectOne((r) => r.url === '/api/stops/import')
      .flush(
        { detail: 'The CSV is not valid, nothing was imported', errors: ['line 2: x'] },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(await outcome).not.toBe('ok');
    http.expectNone('/api/warehouses');
  });
});
