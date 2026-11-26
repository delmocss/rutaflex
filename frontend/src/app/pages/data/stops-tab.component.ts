import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { dayOptionLabel, formatTime, windowLabel } from '../../core/domain';
import { toLocation, toNumber } from '../../core/forms';
import { Stop } from '../../core/models';
import { PlanStore } from '../../core/plan.store';
import { ImportPanelComponent } from '../../features/sidebar/import-panel.component';

/** Paradas del día elegido: alta, edición y borrado, además de la importación desde CSV. */
@Component({
  selector: 'app-stops-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ImportPanelComponent],
  template: `
    <div class="toolbar">
      <label class="field">
        Día
        <select
          (change)="onDay($event)"
          [value]="store.selectedDate()"
          [disabled]="store.days().length === 0"
        >
          @for (day of store.days(); track day.date) {
            <option [value]="day.date" [selected]="day.date === store.selectedDate()">
              {{ dayLabel(day) }}
            </option>
          } @empty {
            <option value="">Sin días con paradas</option>
          }
        </select>
      </label>
    </div>

    <div class="layout">
      <div>
        <div class="card table-card">
          <div class="scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Dirección</th>
                  <th scope="col">Bultos</th>
                  <th scope="col">Franja</th>
                  <th scope="col"><span class="sr-only">Acciones</span></th>
                </tr>
              </thead>
              <tbody>
                @for (stop of store.stops(); track stop.id) {
                  <tr>
                    <td>{{ stop.address }}</td>
                    <td class="num">{{ stop.parcels }}</td>
                    <td class="num">{{ window(stop) ?? '—' }}</td>
                    <td class="row-actions">
                      @if (confirming() === stop.id) {
                        <span class="confirm">¿Eliminar?</span>
                        <button type="button" class="btn-link danger" (click)="remove(stop)">
                          Sí, eliminar
                        </button>
                        <button type="button" class="btn-link" (click)="confirming.set(null)">
                          No
                        </button>
                      } @else {
                        <button type="button" class="btn-link" (click)="edit(stop)">Editar</button>
                        <button
                          type="button"
                          class="btn-link danger"
                          (click)="confirming.set(stop.id)"
                        >
                          Eliminar
                        </button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4" class="empty">
                      No hay paradas en este día. Añade una con el formulario o importa un CSV.
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>

        <details class="card import">
          <summary>Importar paradas desde CSV</summary>
          <app-import-panel />
        </details>
      </div>

      <form class="card form" (ngSubmit)="save()" #f="ngForm">
        <h3>{{ editingId() ? 'Editar parada' : 'Nueva parada' }}</h3>
        <label class="field">
          Día de reparto
          <input type="date" name="date" [(ngModel)]="form.date" required />
        </label>
        <label class="field">
          Dirección
          <input type="text" name="address" [(ngModel)]="form.address" required maxlength="200" />
        </label>
        <div class="pair">
          <label class="field">
            Latitud
            <input
              type="text"
              name="lat"
              [(ngModel)]="form.lat"
              required
              inputmode="decimal"
              placeholder="40.2428"
            />
          </label>
          <label class="field">
            Longitud
            <input
              type="text"
              name="lon"
              [(ngModel)]="form.lon"
              required
              inputmode="decimal"
              placeholder="-3.6997"
            />
          </label>
        </div>
        <label class="field">
          Bultos
          <input
            type="number"
            name="parcels"
            [(ngModel)]="form.parcels"
            required
            min="1"
            step="1"
          />
        </label>
        <div class="pair">
          <label class="field">
            Franja desde
            <input type="time" name="windowStart" [(ngModel)]="form.windowStart" />
          </label>
          <label class="field">
            hasta
            <input type="time" name="windowEnd" [(ngModel)]="form.windowEnd" />
          </label>
        </div>
        <p class="hint">La franja es opcional, pero va completa: las dos horas o ninguna.</p>

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
  `,
  styleUrl: './data-forms.css',
})
export class StopsTabComponent {
  private readonly api = inject(ApiService);
  protected readonly store = inject(PlanStore);
  protected readonly dayLabel = dayOptionLabel;
  protected readonly window = windowLabel;

  protected readonly form = this.emptyForm();
  protected readonly editingId = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  protected onDay(event: Event): void {
    const date = (event.target as HTMLSelectElement).value;
    void this.store.selectDate(date);
    if (!this.editingId()) {
      this.form.date = date;
    }
  }

  protected edit(stop: Stop): void {
    this.editingId.set(stop.id);
    this.confirming.set(null);
    this.error.set('');
    Object.assign(this.form, {
      date: stop.deliveryDate,
      address: stop.address,
      lat: String(stop.location.lat),
      lon: String(stop.location.lon),
      parcels: String(stop.parcels),
      windowStart: formatTime(stop.windowStart),
      windowEnd: formatTime(stop.windowEnd),
    });
  }

  protected reset(): void {
    this.editingId.set(null);
    this.error.set('');
    Object.assign(this.form, this.emptyForm());
  }

  protected async save(): Promise<void> {
    this.error.set('');
    const location = toLocation(this.form.lat, this.form.lon);
    if (typeof location === 'string') {
      this.error.set(location);
      return;
    }
    const parcels = toNumber(this.form.parcels);
    if (parcels === null || !Number.isInteger(parcels) || parcels < 1) {
      this.error.set('Los bultos deben ser un número entero, al menos 1.');
      return;
    }
    const { windowStart, windowEnd } = this.form;
    if (!!windowStart !== !!windowEnd) {
      this.error.set('La franja horaria debe tener hora de inicio y de fin, o ninguna.');
      return;
    }
    if (windowStart && windowStart >= windowEnd) {
      this.error.set('La franja horaria debe empezar antes de acabar.');
      return;
    }
    const body = {
      deliveryDate: this.form.date,
      address: this.form.address.trim(),
      location,
      parcels,
      windowStart: windowStart || null,
      windowEnd: windowEnd || null,
    };
    const date = this.form.date;
    await this.run(date, async () => {
      const id = this.editingId();
      await firstValueFrom(id ? this.api.updateStop(id, body) : this.api.createStop(body));
      this.reset();
      this.form.date = date;
    });
  }

  protected async remove(stop: Stop): Promise<void> {
    this.confirming.set(null);
    await this.run(this.store.selectedDate() ?? undefined, async () => {
      await firstValueFrom(this.api.deleteStop(stop.id));
      if (this.editingId() === stop.id) {
        this.reset();
      }
    });
  }

  private async run(date: string | undefined, action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
      await this.store.load(date);
    } catch (error) {
      const problem = describeError(error);
      this.error.set([problem.message, ...problem.details].join(' '));
    } finally {
      this.busy.set(false);
    }
  }

  private emptyForm() {
    return {
      date: this.store.selectedDate() ?? new Date().toISOString().slice(0, 10),
      address: '',
      lat: '',
      lon: '',
      parcels: '1',
      windowStart: '',
      windowEnd: '',
    };
  }
}
