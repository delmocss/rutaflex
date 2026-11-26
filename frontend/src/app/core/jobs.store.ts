import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiProblem, describeError } from './api-error';
import { ApiService } from './api.service';
import { isActive } from './domain';
import { OptimizationJob } from './models';

/** Cada cuanto se consulta un trabajo en curso. Un token para poder poner 0 en los tests. */
export const POLL_INTERVAL_MS = new InjectionToken<number>('POLL_INTERVAL_MS', {
  providedIn: 'root',
  factory: () => 1500,
});

/** Consultas seguidas que pueden fallar antes de dejar de seguir un trabajo. */
const MAX_POLL_FAILURES = 4;

/**
 * Optimizaciones: lanzar una, seguirla hasta que termina y consultar las anteriores.
 *
 * Solo se sigue un trabajo a la vez ({@link current}). Cada operacion que cambia cual se sigue lleva un numero
 * de orden: si cuando llega una respuesta ya se ha pedido otra cosa (otro dia, otro trabajo), se descarta, y el
 * bucle de consulta del trabajo anterior se detiene solo.
 */
@Injectable({ providedIn: 'root' })
export class JobsStore {
  private readonly api = inject(ApiService);
  private readonly pollMs = inject(POLL_INTERVAL_MS);
  private order = 0;

  /** Los trabajos recientes (sin rutas), los mas nuevos primero. */
  readonly jobs = signal<OptimizationJob[]>([]);
  /** El trabajo que se esta mirando, con sus rutas si esta completado. */
  readonly current = signal<OptimizationJob | null>(null);
  readonly loading = signal(false);
  readonly requesting = signal(false);
  readonly error = signal<ApiProblem | null>(null);

  /** Hay un trabajo en cola o calculando. */
  readonly active = computed(() => {
    const job = this.current();
    return job ? isActive(job.status) : false;
  });

  async refreshList(date?: string): Promise<void> {
    try {
      this.jobs.set(await firstValueFrom(this.api.optimizations(date)));
    } catch (error) {
      this.error.set(describeError(error));
    }
  }

  /** Lanza una optimizacion y la sigue. Devuelve false si la API la rechaza (el motivo queda en `error`). */
  async request(date: string, warehouseId?: string): Promise<boolean> {
    const order = ++this.order;
    this.error.set(null);
    this.loading.set(false);
    this.requesting.set(true);
    try {
      const job = await firstValueFrom(this.api.requestOptimization(date, warehouseId));
      if (order !== this.order) {
        return true;
      }
      this.current.set(job);
      void this.follow(job.id, order);
      return true;
    } catch (error) {
      if (order === this.order) {
        this.error.set(describeError(error));
      }
      return false;
    } finally {
      this.requesting.set(false);
    }
  }

  /** Muestra un trabajo (con sus rutas); si esta en curso, lo sigue. */
  async open(id: string): Promise<void> {
    const order = ++this.order;
    this.error.set(null);
    this.loading.set(true);
    try {
      const job = await firstValueFrom(this.api.optimization(id));
      if (order !== this.order) {
        return;
      }
      this.current.set(job);
      if (isActive(job.status)) {
        void this.follow(job.id, order);
      }
    } catch (error) {
      if (order === this.order) {
        this.error.set(describeError(error));
      }
    } finally {
      if (order === this.order) {
        this.loading.set(false);
      }
    }
  }

  /**
   * Muestra el trabajo mas reciente de un dia (y de un almacen, si se indica), o nada si ese dia no se ha
   * optimizado nunca.
   */
  async openLatestFor(date: string, warehouseId?: string): Promise<void> {
    const order = ++this.order;
    this.error.set(null);
    this.current.set(null);
    this.loading.set(true);
    try {
      const jobs = await firstValueFrom(this.api.optimizations(date, 10));
      if (order !== this.order) {
        return;
      }
      const latest = jobs.find(
        (candidate) => !warehouseId || candidate.warehouseId === warehouseId,
      );
      if (!latest) {
        return;
      }
      const job = await firstValueFrom(this.api.optimization(latest.id));
      if (order !== this.order) {
        return;
      }
      this.current.set(job);
      if (isActive(job.status)) {
        void this.follow(job.id, order);
      }
    } catch (error) {
      if (order === this.order) {
        this.error.set(describeError(error));
      }
    } finally {
      if (order === this.order) {
        this.loading.set(false);
      }
    }
  }

  /** Deja de mirar el trabajo actual (por ejemplo, al salir de la pantalla). */
  stop(): void {
    this.order++;
    this.current.set(null);
    this.loading.set(false);
  }

  /**
   * Consulta el trabajo hasta que termina, mientras nadie haya pedido otra cosa. Un fallo puntual (un corte
   * de red, un 502) no abandona el seguimiento: se reintenta unas veces con pausa creciente antes de rendirse.
   * Si se rinde, el error queda visible y el trabajo sigue marcado como activo: se retoma con {@link open}.
   */
  private async follow(id: string, order: number): Promise<void> {
    let failures = 0;
    while (order === this.order) {
      await new Promise((resolve) => setTimeout(resolve, this.pollMs * (1 + failures)));
      if (order !== this.order) {
        return;
      }
      try {
        const job = await firstValueFrom(this.api.optimization(id));
        if (order !== this.order) {
          return;
        }
        failures = 0;
        this.error.set(null);
        this.current.set(job);
        if (!isActive(job.status)) {
          void this.refreshList();
          return;
        }
      } catch (error) {
        if (order !== this.order) {
          return;
        }
        failures++;
        if (failures >= MAX_POLL_FAILURES) {
          this.error.set(describeError(error));
          return;
        }
      }
    }
  }
}
