import { HttpRequest, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { JobsStore, POLL_INTERVAL_MS } from './jobs.store';
import { JobStatus, OptimizationJob } from './models';

const job = (
  id: string,
  status: JobStatus,
  extra: Partial<OptimizationJob> = {},
): OptimizationJob => ({
  id,
  warehouseId: 'w1',
  deliveryDate: '2026-12-14',
  status,
  requestedAt: '2026-12-14T10:00:00Z',
  ...extra,
});

describe('JobsStore', () => {
  let store: JobsStore;
  let http: HttpTestingController;
  let queue: TestRequest[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: POLL_INTERVAL_MS, useValue: 0 },
      ],
    });
    store = TestBed.inject(JobsStore);
    http = TestBed.inject(HttpTestingController);
    queue = [];
  });

  afterEach(() => {
    store.stop();
    expect(queue.map((r) => r.request.urlWithParams)).toEqual([]);
    http.verify();
  });

  /** Espera a que exista una peticion que cumpla el predicado y la devuelve (en orden de envio). */
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
  const post = (url: string) => (r: HttpRequest<unknown>) => r.method === 'POST' && r.url === url;

  it('lanza una optimizacion, la sigue y termina cuando se completa', async () => {
    const requesting = store.request('2026-12-14', 'w1');
    const created = await request(post('/api/optimizations'));
    expect(created.request.body).toEqual({ deliveryDate: '2026-12-14', warehouseId: 'w1' });
    created.flush(job('j1', 'QUEUED'));
    expect(await requesting).toBe(true);
    expect(store.current()?.status).toBe('QUEUED');
    expect(store.active()).toBe(true);

    (await request(get('/api/optimizations/j1'))).flush(job('j1', 'RUNNING'));
    await vi.waitFor(() => expect(store.current()?.status).toBe('RUNNING'));

    (await request(get('/api/optimizations/j1'))).flush(
      job('j1', 'COMPLETED', { totalKm: 120, baselineKm: 800, routes: [] }),
    );
    await vi.waitFor(() => expect(store.current()?.status).toBe('COMPLETED'));
    expect(store.active()).toBe(false);
    // Al terminar refresca la lista, y ya no vuelve a consultar el trabajo.
    (await request(get('/api/optimizations'))).flush([job('j1', 'COMPLETED')]);
    await vi.waitFor(() => expect(store.jobs()).toHaveLength(1));
  });

  it('un trabajo fallido termina el seguimiento y conserva el motivo', async () => {
    const requesting = store.request('2026-12-14');
    (await request(post('/api/optimizations'))).flush(job('j1', 'QUEUED'));
    await requesting;

    (await request(get('/api/optimizations/j1'))).flush(
      job('j1', 'FAILED', { failureReason: 'No feasible plan found (hard score -3)' }),
    );
    await vi.waitFor(() => expect(store.current()?.status).toBe('FAILED'));
    expect(store.current()?.failureReason).toContain('No feasible plan');
    (await request(get('/api/optimizations'))).flush([]);
  });

  it('si la API rechaza la peticion devuelve false y deja el motivo en espanol', async () => {
    const requesting = store.request('2026-12-14');
    (await request(post('/api/optimizations'))).flush(
      { detail: 'An optimization for this warehouse and day is already in progress' },
      { status: 409, statusText: 'Conflict' },
    );

    expect(await requesting).toBe(false);
    expect(store.error()?.message).toBe(
      'Ya hay un cálculo en curso para este día. Espera a que termine.',
    );
    expect(store.current()).toBeNull();
    expect(store.requesting()).toBe(false);
  });

  it('abrir otro trabajo detiene el seguimiento del anterior (no pisa lo que se mira)', async () => {
    const requesting = store.request('2026-12-14');
    (await request(post('/api/optimizations'))).flush(job('j1', 'QUEUED'));
    await requesting;
    const staleFollow = await request(get('/api/optimizations/j1'));

    const opening = store.open('j2');
    const second = await request(get('/api/optimizations/j2'));
    second.flush(job('j2', 'COMPLETED', { routes: [] }));
    await opening;

    // Llega tarde la respuesta del seguimiento del primero: se descarta.
    staleFollow.flush(job('j1', 'COMPLETED', { totalKm: 1 }));
    await new Promise((resolve) => setTimeout(resolve, 5));

    expect(store.current()?.id).toBe('j2');
    expect(store.current()?.status).toBe('COMPLETED');
  });

  it('openLatestFor muestra el trabajo mas reciente del dia con sus rutas', async () => {
    const opening = store.openLatestFor('2026-12-14');
    const list = await request(get('/api/optimizations'));
    expect(list.request.params.get('date')).toBe('2026-12-14');
    list.flush([job('nuevo', 'COMPLETED'), job('viejo', 'COMPLETED')]);
    (await request(get('/api/optimizations/nuevo'))).flush(
      job('nuevo', 'COMPLETED', { routes: [] }),
    );
    await opening;

    expect(store.current()?.id).toBe('nuevo');
    expect(store.loading()).toBe(false);
  });

  it('openLatestFor sin trabajos deja la pantalla vacia, sin error', async () => {
    store.current.set(job('antiguo', 'COMPLETED'));
    const opening = store.openLatestFor('2026-12-15');
    (await request(get('/api/optimizations'))).flush([]);
    await opening;

    expect(store.current()).toBeNull();
    expect(store.error()).toBeNull();
  });

  it('cambiar de dia rapido: gana el ultimo dia pedido', async () => {
    const first = store.openLatestFor('2026-12-14');
    const second = store.openLatestFor('2026-12-15');
    const slow = await request(
      (r) => r.url === '/api/optimizations' && r.params.get('date') === '2026-12-14',
    );
    const fast = await request(
      (r) => r.url === '/api/optimizations' && r.params.get('date') === '2026-12-15',
    );

    fast.flush([job('del-15', 'COMPLETED')]);
    (await request(get('/api/optimizations/del-15'))).flush(
      job('del-15', 'COMPLETED', { routes: [] }),
    );
    await second;
    slow.flush([job('del-14', 'COMPLETED')]);
    await first;

    expect(store.current()?.id).toBe('del-15');
  });

  it('una peticion pisada por abrir otro trabajo no sobrescribe lo que se mira ni se sigue', async () => {
    const requesting = store.request('2026-12-14');
    const created = await request(post('/api/optimizations'));

    const opening = store.open('j2');
    (await request(get('/api/optimizations/j2'))).flush(job('j2', 'COMPLETED', { routes: [] }));
    await opening;

    created.flush(job('j1', 'QUEUED'));
    await requesting;
    await new Promise((resolve) => setTimeout(resolve, 5));

    expect(store.current()?.id).toBe('j2');
    // No arranco el seguimiento del trabajo desplazado.
    expect(http.match(get('/api/optimizations/j1'))).toEqual([]);
  });

  it('abrir un trabajo lento y despues otro: la respuesta tardia del primero se descarta', async () => {
    const first = store.open('lento');
    const slow = await request(get('/api/optimizations/lento'));
    const second = store.open('rapido');
    (await request(get('/api/optimizations/rapido'))).flush(
      job('rapido', 'COMPLETED', { routes: [] }),
    );
    await second;

    slow.flush(job('lento', 'COMPLETED', { totalKm: 1 }));
    await first;

    expect(store.current()?.id).toBe('rapido');
  });

  it('un error de un trabajo desplazado no se muestra', async () => {
    const first = store.open('lento');
    const slow = await request(get('/api/optimizations/lento'));
    const second = store.open('rapido');
    (await request(get('/api/optimizations/rapido'))).flush(
      job('rapido', 'COMPLETED', { routes: [] }),
    );
    await second;

    slow.flush({ detail: 'x' }, { status: 500, statusText: 'Server Error' });
    await first;

    expect(store.error()).toBeNull();
  });

  it('openLatestFor: si mientras llega el detalle se pide otro dia, el detalle viejo se descarta', async () => {
    const first = store.openLatestFor('2026-12-14');
    (await request(get('/api/optimizations'))).flush([job('del-14', 'COMPLETED')]);
    const staleDetail = await request(get('/api/optimizations/del-14'));

    const second = store.openLatestFor('2026-12-15');
    (await request(get('/api/optimizations'))).flush([]);
    await second;

    staleDetail.flush(job('del-14', 'COMPLETED', { routes: [] }));
    await first;

    expect(store.current()).toBeNull();
  });

  it('si se detiene justo antes de la siguiente consulta, no se consulta', async () => {
    const requesting = store.request('2026-12-14');
    (await request(post('/api/optimizations'))).flush(job('j1', 'QUEUED'));
    await requesting;

    store.stop(); // el temporizador de la primera consulta aun no ha saltado
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(http.match(() => true)).toEqual([]);
  });

  it('stop() cancela el seguimiento y vacia el trabajo actual', async () => {
    const requesting = store.request('2026-12-14');
    (await request(post('/api/optimizations'))).flush(job('j1', 'QUEUED'));
    await requesting;
    const pending = await request(get('/api/optimizations/j1'));

    store.stop();
    pending.flush(job('j1', 'COMPLETED'));
    await new Promise((resolve) => setTimeout(resolve, 5));

    expect(store.current()).toBeNull();
  });
});
