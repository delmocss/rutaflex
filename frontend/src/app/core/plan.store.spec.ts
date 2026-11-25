import { HttpErrorResponse, HttpRequest, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Stop, StopDay, Vehicle, Warehouse } from './models';
import { PlanStore } from './plan.store';

const warehouse: Warehouse = {
  id: 'w1',
  name: 'Almacen',
  address: 'Valdemoro',
  location: { lat: 40.19, lon: -3.65 },
  createdAt: '2026-12-01T10:00:00Z',
};
const second: Warehouse = { ...warehouse, id: 'w2', name: 'Otro' };
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
const stop = (id: string, parcels = 1): Stop => ({
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
  let queue: TestRequest[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(PlanStore);
    http = TestBed.inject(HttpTestingController);
    queue = [];
  });

  afterEach(() => {
    // Ninguna peticion puede quedarse sin responder: seria una promesa colgada que el test no vio.
    expect(queue.map((r) => r.request.urlWithParams)).toEqual([]);
    http.verify();
  });

  /**
   * Espera a que exista una peticion que cumpla el predicado (el store las lanza de forma asincrona)
   * y la devuelve. Las peticiones se guardan en una cola, asi que dos iguales se atienden en orden de envio.
   */
  async function request(predicate: (r: HttpRequest<unknown>) => boolean): Promise<TestRequest> {
    for (let attempt = 0; attempt < 200; attempt++) {
      queue.push(...http.match(() => true));
      const index = queue.findIndex((r) => predicate(r.request));
      if (index >= 0) {
        return queue.splice(index, 1)[0];
      }
      await new Promise((resolve) => setTimeout(resolve));
    }
    throw new Error('La peticion esperada nunca se envio');
  }

  const get = (url: string) => (r: HttpRequest<unknown>) => r.method === 'GET' && r.url === url;
  const stopsOf = (date: string) => (r: HttpRequest<unknown>) =>
    r.method === 'GET' && r.url === '/api/stops' && r.params.get('date') === date;

  /** Responde a todo lo que hace load(): almacenes, dias, y luego flota y paradas. */
  async function answerLoad(
    options: { warehouses?: Warehouse[]; days?: StopDay[]; stops?: Stop[]; date?: string } = {},
  ): Promise<void> {
    const warehouses = options.warehouses ?? [warehouse];
    const stopDays = options.days ?? days;
    (await request(get('/api/warehouses'))).flush(warehouses);
    (await request(get('/api/stops/dates'))).flush(stopDays);
    if (warehouses.length > 0) {
      (await request(get(`/api/warehouses/${warehouses[0].id}/vehicles`))).flush(vehicles);
    }
    if (stopDays.length > 0) {
      const date = options.date ?? stopDays.at(-1)!.date;
      (await request(stopsOf(date))).flush(options.stops ?? [stop('a', 3)]);
    }
  }

  async function loaded(): Promise<void> {
    const loading = store.load();
    await answerLoad();
    await loading;
  }

  describe('load', () => {
    it('elige el dia mas reciente y deja paradas y resumen listos', async () => {
      const loading = store.load();
      await answerLoad();

      expect(await loading).toBe(true);
      expect(store.warehouse()?.id).toBe('w1');
      expect(store.vehicles()).toHaveLength(1);
      expect(store.selectedDate()).toBe('2026-12-15');
      expect(store.summary()).toMatchObject({ stops: 1, parcels: 3, capacity: 60 });
      expect(store.loading()).toBe(false);
      expect(store.error()).toBeNull();
    });

    it('sin datos queda vacio (para explicar como cargarlos), no en error', async () => {
      const loading = store.load();
      await answerLoad({ warehouses: [], days: [] });
      await loading;

      expect(store.isEmpty()).toBe(true);
      expect(store.error()).toBeNull();
    });

    it('si la API falla guarda un error mostrable, no toca lo que habia y deja de cargar', async () => {
      await loaded();

      const loading = store.load();
      (await request(get('/api/warehouses'))).error(new ProgressEvent('error'), { status: 0 });
      (await request(get('/api/stops/dates'))).error(new ProgressEvent('error'), { status: 0 });

      expect(await loading).toBe(false);
      expect(store.error()?.message).toContain('No se puede conectar');
      expect(store.loading()).toBe(false);
      expect(store.stops()).toHaveLength(1);
      expect(store.vehicles()).toHaveLength(1);
    });

    it('confirma todo junto: hasta que no llegan todas las respuestas no cambia nada', async () => {
      await loaded();
      const before = store.stops();

      const loading = store.load();
      (await request(get('/api/warehouses'))).flush([warehouse]);
      (await request(get('/api/stops/dates'))).flush(days);
      (await request(get('/api/warehouses/w1/vehicles'))).flush(vehicles);
      expect(store.stops()).toBe(before);

      (await request(stopsOf('2026-12-15'))).flush([stop('nuevo')]);
      await loading;
      expect(store.stops()[0].id).toBe('nuevo');
    });
  });

  describe('selectDate', () => {
    it('vacia las paradas al momento (la etiqueta nunca muestra datos de otro dia) y las rellena al llegar', async () => {
      await loaded();
      store.selectStop('a');

      const selecting = store.selectDate('2026-12-14');
      expect(store.selectedDate()).toBe('2026-12-14');
      expect(store.stops()).toEqual([]);
      expect(store.selectedStopId()).toBeNull();

      (await request(stopsOf('2026-12-14'))).flush([stop('x', 2), stop('y', 3)]);
      await selecting;
      expect(store.stops()).toHaveLength(2);
    });

    it('si dos dias se piden seguidos y las respuestas llegan al reves, gana el ultimo pedido', async () => {
      await loaded();

      const first = store.selectDate('2026-12-14');
      const last = store.selectDate('2026-12-15');
      const slow = await request(stopsOf('2026-12-14'));
      const fast = await request(stopsOf('2026-12-15'));

      fast.flush([stop('del-15')]);
      await last;
      slow.flush([stop('del-14-tardia')]);
      await first;

      expect(store.selectedDate()).toBe('2026-12-15');
      expect(store.stops().map((s) => s.id)).toEqual(['del-15']);
    });

    it('si falla, se queda vacio con el error: nunca con las paradas del dia anterior', async () => {
      await loaded();

      const selecting = store.selectDate('2026-12-14');
      (await request(stopsOf('2026-12-14'))).flush(
        { detail: 'Internal server error' },
        { status: 500, statusText: 'Server Error' },
      );
      await selecting;

      expect(store.selectedDate()).toBe('2026-12-14');
      expect(store.stops()).toEqual([]);
      expect(store.error()).not.toBeNull();
    });

    it('una carga completa pisada por una seleccion posterior no sobrescribe el resultado', async () => {
      await loaded();

      const loading = store.load();
      (await request(get('/api/warehouses'))).flush([warehouse]);
      (await request(get('/api/stops/dates'))).flush(days);
      const selecting = store.selectDate('2026-12-14');

      // La carga decide el dia al recibir la lista (ya es el 14): pide tambien las paradas del 14.
      (await request(stopsOf('2026-12-14'))).flush([stop('elegido')]);
      await selecting;
      (await request(get('/api/warehouses/w1/vehicles'))).flush(vehicles);
      (await request(stopsOf('2026-12-14'))).flush([stop('carga-vieja')]);

      expect(await loading).toBe(false);
      expect(store.selectedDate()).toBe('2026-12-14');
      expect(store.stops().map((s) => s.id)).toEqual(['elegido']);
    });

    it('con dos peticiones a la vez el indicador de carga sigue activo hasta que acaban las dos', async () => {
      await loaded();

      const a = store.selectDate('2026-12-14');
      const b = store.selectDate('2026-12-15');
      const first = await request(stopsOf('2026-12-14'));
      const second = await request(stopsOf('2026-12-15'));
      expect(store.loading()).toBe(true);

      first.flush([]);
      await a;
      expect(store.loading()).toBe(true);

      second.flush([]);
      await b;
      expect(store.loading()).toBe(false);
    });
  });

  describe('selectWarehouse', () => {
    it('vacia la flota al momento y si falla no deja la del almacen anterior', async () => {
      const loading = store.load();
      await answerLoad({ warehouses: [warehouse, second] });
      await loading;

      const selecting = store.selectWarehouse('w2');
      expect(store.warehouse()?.id).toBe('w2');
      expect(store.vehicles()).toEqual([]);

      (await request(get('/api/warehouses/w2/vehicles'))).flush(
        { detail: 'Internal server error' },
        { status: 500, statusText: 'Server Error' },
      );
      await selecting;

      expect(store.vehicles()).toEqual([]);
      expect(store.summary().capacity).toBe(0);
      expect(store.error()).not.toBeNull();
    });
  });

  describe('importCsv', () => {
    const file = new File(['address,lat,lon,parcels\n'], 'stops.csv', { type: 'text/csv' });

    it('envia el CSV con el dia y replace, recarga y deja elegido el dia importado', async () => {
      await loaded();

      const importing = store.importCsv('2026-12-14', file, true);
      const upload = await request((r) => r.method === 'POST' && r.url === '/api/stops/import');
      expect(upload.request.params.get('date')).toBe('2026-12-14');
      expect(upload.request.params.get('replace')).toBe('true');
      expect(upload.request.body).toBeInstanceOf(FormData);
      upload.flush({ date: '2026-12-14', imported: 2, replaced: 2 });

      await answerLoad({ date: '2026-12-14', stops: [stop('importada')] });
      const outcome = await importing;

      expect(outcome.reloaded).toBe(true);
      expect(outcome.result.imported).toBe(2);
      expect(store.selectedDate()).toBe('2026-12-14');
    });

    it('un CSV rechazado lanza el error HTTP original y no recarga nada', async () => {
      await loaded();
      const importing = store.importCsv('2026-12-14', file, false);
      const outcome = importing.then(
        () => null,
        (error: unknown) => error,
      );

      (await request((r) => r.method === 'POST')).flush(
        { detail: 'The CSV is not valid, nothing was imported', errors: ['line 2: x'] },
        { status: 400, statusText: 'Bad Request' },
      );

      const error = await outcome;
      expect(error).toBeInstanceOf(HttpErrorResponse);
      expect((error as HttpErrorResponse).status).toBe(400);
      http.expectNone(get('/api/warehouses'));
    });

    it('si se importa pero la recarga falla, lo dice (reloaded=false) y deja el error visible', async () => {
      await loaded();

      const importing = store.importCsv('2026-12-14', file, false);
      (await request((r) => r.method === 'POST')).flush({
        date: '2026-12-14',
        imported: 1,
        replaced: 0,
      });
      (await request(get('/api/warehouses'))).error(new ProgressEvent('error'), { status: 0 });
      (await request(get('/api/stops/dates'))).error(new ProgressEvent('error'), { status: 0 });
      const outcome = await importing;

      expect(outcome.result.imported).toBe(1);
      expect(outcome.reloaded).toBe(false);
      expect(store.error()).not.toBeNull();
    });
  });
});
