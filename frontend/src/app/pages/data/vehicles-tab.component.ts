import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { formatTime } from '../../core/domain';
import { toNumber } from '../../core/forms';
import { Vehicle } from '../../core/models';
import { PlanStore } from '../../core/plan.store';

const EMPTY = { name: '', capacity: '60', shiftStart: '08:00', shiftEnd: '15:00' };

/** Flota del almacén elegido: alta, edición y borrado de furgonetas. */
@Component({
  selector: 'app-vehicles-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    @if (!store.warehouse()) {
      <div class="card empty">Primero crea un almacén en la pestaña «Almacén».</div>
    } @else {
      <div class="layout">
        <div class="card table-card">
          <table>
            <thead>
              <tr>
                <th scope="col">Furgoneta</th>
                <th scope="col">Capacidad</th>
                <th scope="col">Jornada</th>
                <th scope="col"><span class="sr-only">Acciones</span></th>
              </tr>
            </thead>
            <tbody>
              @for (vehicle of store.vehicles(); track vehicle.id) {
                <tr>
                  <td>{{ vehicle.name }}</td>
                  <td class="num">{{ vehicle.capacityParcels }} bultos</td>
                  <td class="num">{{ time(vehicle.shiftStart) }}–{{ time(vehicle.shiftEnd) }}</td>
                  <td class="row-actions">
                    @if (confirming() === vehicle.id) {
                      <span class="confirm">¿Eliminar?</span>
                      <button type="button" class="btn-link danger" (click)="remove(vehicle)">
                        Sí, eliminar
                      </button>
                      <button type="button" class="btn-link" (click)="confirming.set(null)">
                        No
                      </button>
                    } @else {
                      <button type="button" class="btn-link" (click)="edit(vehicle)">Editar</button>
                      <button
                        type="button"
                        class="btn-link danger"
                        (click)="confirming.set(vehicle.id)"
                      >
                        Eliminar
                      </button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="4" class="empty">
                    Este almacén no tiene furgonetas. Añade la primera con el formulario.
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <form class="card form" (ngSubmit)="save()" #f="ngForm">
          <h3>{{ editingId() ? 'Editar furgoneta' : 'Nueva furgoneta' }}</h3>
          <label class="field">
            Nombre
            <input type="text" name="name" [(ngModel)]="form.name" required maxlength="80" />
          </label>
          <label class="field">
            Capacidad (bultos)
            <input
              type="number"
              name="capacity"
              [(ngModel)]="form.capacity"
              required
              min="1"
              step="1"
            />
          </label>
          <div class="pair">
            <label class="field">
              Sale a las
              <input type="time" name="start" [(ngModel)]="form.shiftStart" required />
            </label>
            <label class="field">
              Vuelve antes de
              <input type="time" name="end" [(ngModel)]="form.shiftEnd" required />
            </label>
          </div>

          @if (error()) {
            <div class="alert" role="alert">{{ error() }}</div>
          }

          <div class="actions">
            <button type="submit" class="btn btn-primary" [disabled]="busy() || f.invalid">
              {{ busy() ? 'Guardando…' : editingId() ? 'Guardar' : 'Añadir' }}
            </button>
            @if (editingId()) {
              <button type="button" class="btn" (click)="reset()">Cancelar</button>
            }
          </div>
        </form>
      </div>
    }
  `,
  styleUrl: './data-forms.css',
})
export class VehiclesTabComponent {
  private readonly api = inject(ApiService);
  protected readonly store = inject(PlanStore);
  protected readonly time = formatTime;

  protected readonly form = { ...EMPTY };
  protected readonly editingId = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  protected edit(vehicle: Vehicle): void {
    this.editingId.set(vehicle.id);
    this.confirming.set(null);
    this.error.set('');
    Object.assign(this.form, {
      name: vehicle.name,
      capacity: String(vehicle.capacityParcels),
      shiftStart: formatTime(vehicle.shiftStart),
      shiftEnd: formatTime(vehicle.shiftEnd),
    });
  }

  protected reset(): void {
    this.editingId.set(null);
    this.error.set('');
    Object.assign(this.form, EMPTY);
  }

  protected async save(): Promise<void> {
    this.error.set('');
    const capacity = toNumber(this.form.capacity);
    if (capacity === null || !Number.isInteger(capacity) || capacity < 1) {
      this.error.set('La capacidad debe ser un número entero de bultos, al menos 1.');
      return;
    }
    if (this.form.shiftStart >= this.form.shiftEnd) {
      this.error.set('La hora de salida debe ser anterior a la de vuelta.');
      return;
    }
    const warehouse = this.store.warehouse();
    if (!warehouse) {
      return;
    }
    const body = {
      name: this.form.name.trim(),
      capacityParcels: capacity,
      shiftStart: this.form.shiftStart,
      shiftEnd: this.form.shiftEnd,
    };
    await this.run(async () => {
      const id = this.editingId();
      await firstValueFrom(
        id ? this.api.updateVehicle(id, body) : this.api.createVehicle(warehouse.id, body),
      );
      this.reset();
    });
  }

  protected async remove(vehicle: Vehicle): Promise<void> {
    this.confirming.set(null);
    await this.run(async () => {
      await firstValueFrom(this.api.deleteVehicle(vehicle.id));
      if (this.editingId() === vehicle.id) {
        this.reset();
      }
    });
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
      await this.store.load();
    } catch (error) {
      const problem = describeError(error);
      this.error.set([problem.message, ...problem.details].join(' '));
    } finally {
      this.busy.set(false);
    }
  }
}
