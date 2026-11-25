import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiProblem, describeError } from './api-error';
import { ApiService } from './api.service';
import { summarize } from './domain';
import { ImportResult, Stop, StopDay, Vehicle, Warehouse } from './models';

/** Estado de la pantalla: almacen, flota, dias con paradas y las paradas del dia elegido. */
@Injectable({ providedIn: 'root' })
export class PlanStore {
  private readonly api = inject(ApiService);

  readonly warehouses = signal<Warehouse[]>([]);
  readonly warehouse = signal<Warehouse | null>(null);
  readonly vehicles = signal<Vehicle[]>([]);
  readonly days = signal<StopDay[]>([]);
  readonly selectedDate = signal<string | null>(null);
  readonly stops = signal<Stop[]>([]);
  readonly selectedStopId = signal<string | null>(null);

  readonly loading = signal(false);
  readonly error = signal<ApiProblem | null>(null);

  readonly summary = computed(() => summarize(this.stops(), this.vehicles()));
  /** Sin almacen ni paradas: la API esta vacia y hay que cargar datos. */
  readonly isEmpty = computed(
    () =>
      !this.loading() &&
      !this.error() &&
      this.warehouses().length === 0 &&
      this.days().length === 0,
  );

  /** Carga todo. Conserva el dia elegido si sigue existiendo; si no, el mas reciente. */
  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [warehouses, days] = await Promise.all([
        firstValueFrom(this.api.warehouses()),
        firstValueFrom(this.api.stopDays()),
      ]);
      this.warehouses.set(warehouses);
      this.days.set(days);

      const current = this.warehouse();
      const warehouse = warehouses.find((w) => w.id === current?.id) ?? warehouses[0] ?? null;
      await this.applyWarehouse(warehouse);

      const previous = this.selectedDate();
      const date = days.some((d) => d.date === previous) ? previous : (days.at(-1)?.date ?? null);
      await this.applyDate(date);
    } catch (error) {
      this.error.set(describeError(error));
    } finally {
      this.loading.set(false);
    }
  }

  async selectDate(date: string): Promise<void> {
    this.error.set(null);
    try {
      await this.applyDate(date);
    } catch (error) {
      this.error.set(describeError(error));
    }
  }

  async selectWarehouse(id: string): Promise<void> {
    this.error.set(null);
    try {
      await this.applyWarehouse(this.warehouses().find((w) => w.id === id) ?? null);
    } catch (error) {
      this.error.set(describeError(error));
    }
  }

  selectStop(id: string | null): void {
    this.selectedStopId.set(id);
  }

  /** Importa un CSV y deja seleccionado el dia importado. Lanza el error de la API para que lo muestre quien llama. */
  async importCsv(date: string, file: File, replace: boolean): Promise<ImportResult> {
    const result = await firstValueFrom(this.api.importStops(date, file, replace));
    this.selectedDate.set(result.date);
    await this.load();
    return result;
  }

  private async applyWarehouse(warehouse: Warehouse | null): Promise<void> {
    this.warehouse.set(warehouse);
    this.vehicles.set(warehouse ? await firstValueFrom(this.api.vehicles(warehouse.id)) : []);
  }

  private async applyDate(date: string | null): Promise<void> {
    this.selectedDate.set(date);
    this.selectedStopId.set(null);
    this.stops.set(date ? await firstValueFrom(this.api.stops(date)) : []);
  }
}
