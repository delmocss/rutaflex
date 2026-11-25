import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiProblem, describeError } from './api-error';
import { ApiService } from './api.service';
import { summarize } from './domain';
import { ImportResult, Stop, StopDay, Vehicle, Warehouse } from './models';

export interface ImportOutcome {
  result: ImportResult;
  /** false si el CSV se importo pero no se pudo recargar la vista (el error queda en `store.error`). */
  reloaded: boolean;
}

/**
 * Estado de la pantalla: almacen, flota, dias con paradas y las paradas del dia elegido.
 *
 * Reglas que evitan estados incoherentes:
 * - Cada operacion que cambia la vista lleva un numero de orden; si cuando llega su respuesta ya hay
 *   una operacion mas nueva, el resultado se descarta (respuestas fuera de orden).
 * - La etiqueta nunca muestra datos de otra: al elegir un dia o un almacen se vacian sus datos al
 *   momento y se rellenan al llegar; si la peticion falla se queda vacio con el error, no con los datos viejos.
 * - La carga completa confirma todo junto al final: o se ve el estado nuevo entero o el anterior.
 */
@Injectable({ providedIn: 'root' })
export class PlanStore {
  private readonly api = inject(ApiService);
  private latest = 0;
  private readonly pending = signal(0);

  readonly warehouses = signal<Warehouse[]>([]);
  readonly warehouse = signal<Warehouse | null>(null);
  readonly vehicles = signal<Vehicle[]>([]);
  readonly days = signal<StopDay[]>([]);
  readonly selectedDate = signal<string | null>(null);
  readonly stops = signal<Stop[]>([]);
  readonly selectedStopId = signal<string | null>(null);

  /** Hay alguna peticion en curso (contador: dos cargas solapadas no se pisan el indicador). */
  readonly loading = computed(() => this.pending() > 0);
  readonly error = signal<ApiProblem | null>(null);

  readonly summary = computed(() => summarize(this.stops(), this.vehicles()));
  readonly selectedStop = computed(
    () => this.stops().find((stop) => stop.id === this.selectedStopId()) ?? null,
  );
  /** Sin almacen ni paradas: la API esta vacia y hay que cargar datos. */
  readonly isEmpty = computed(
    () =>
      !this.loading() &&
      !this.error() &&
      this.warehouses().length === 0 &&
      this.days().length === 0,
  );

  /**
   * Carga todo. Conserva el dia elegido (o `preferDate`) si existe; si no, el mas reciente.
   * Devuelve false si fallo o si otra operacion mas nueva la sustituyo.
   */
  async load(preferDate?: string): Promise<boolean> {
    const order = ++this.latest;
    this.pending.update((n) => n + 1);
    this.error.set(null);
    try {
      const [warehouses, days] = await Promise.all([
        firstValueFrom(this.api.warehouses()),
        firstValueFrom(this.api.stopDays()),
      ]);
      const current = this.warehouse();
      const warehouse = warehouses.find((w) => w.id === current?.id) ?? warehouses[0] ?? null;
      const wanted = preferDate ?? this.selectedDate();
      const date = days.some((d) => d.date === wanted) ? wanted : (days.at(-1)?.date ?? null);

      const [vehicles, stops] = await Promise.all([
        warehouse ? firstValueFrom(this.api.vehicles(warehouse.id)) : Promise.resolve([]),
        date ? firstValueFrom(this.api.stops(date)) : Promise.resolve([]),
      ]);

      if (order !== this.latest) {
        return false;
      }
      this.warehouses.set(warehouses);
      this.days.set(days);
      this.warehouse.set(warehouse);
      this.vehicles.set(vehicles);
      if (date !== this.selectedDate()) {
        this.selectedStopId.set(null);
      }
      this.selectedDate.set(date);
      this.stops.set(stops);
      return true;
    } catch (error) {
      if (order === this.latest) {
        this.error.set(describeError(error));
      }
      return false;
    } finally {
      this.pending.update((n) => n - 1);
    }
  }

  async selectDate(date: string): Promise<void> {
    const order = ++this.latest;
    this.error.set(null);
    this.selectedDate.set(date);
    this.selectedStopId.set(null);
    this.stops.set([]);

    this.pending.update((n) => n + 1);
    try {
      const stops = await firstValueFrom(this.api.stops(date));
      if (order === this.latest) {
        this.stops.set(stops);
      }
    } catch (error) {
      if (order === this.latest) {
        this.error.set(describeError(error));
      }
    } finally {
      this.pending.update((n) => n - 1);
    }
  }

  async selectWarehouse(id: string): Promise<void> {
    const warehouse = this.warehouses().find((w) => w.id === id) ?? null;
    const order = ++this.latest;
    this.error.set(null);
    this.warehouse.set(warehouse);
    this.vehicles.set([]);
    if (!warehouse) {
      return;
    }

    this.pending.update((n) => n + 1);
    try {
      const vehicles = await firstValueFrom(this.api.vehicles(warehouse.id));
      if (order === this.latest) {
        this.vehicles.set(vehicles);
      }
    } catch (error) {
      if (order === this.latest) {
        this.error.set(describeError(error));
      }
    } finally {
      this.pending.update((n) => n - 1);
    }
  }

  selectStop(id: string | null): void {
    this.selectedStopId.set(id);
  }

  /**
   * Importa un CSV. Si la API lo rechaza, lanza el error para que lo muestre quien llama. Si lo
   * importa pero no se puede recargar la vista, lo dice en `reloaded` (y el error queda en `error`).
   */
  async importCsv(date: string, file: File, replace: boolean): Promise<ImportOutcome> {
    const result = await firstValueFrom(this.api.importStops(date, file, replace));
    const reloaded = await this.load(result.date);
    return { result, reloaded };
  }
}
