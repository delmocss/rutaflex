import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { toLocation } from '../../core/forms';
import { PlanStore } from '../../core/plan.store';

/** Alta y edición del almacén: el punto de salida y de vuelta de todas las furgonetas. */
@Component({
  selector: 'app-warehouse-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <form class="card form" (ngSubmit)="save()" #f="ngForm">
      <header>
        <h3>{{ editingId() ? 'Almacén' : 'Nuevo almacén' }}</h3>
        <p class="hint">
          El punto donde empiezan y terminan todas las rutas. Las coordenadas se pueden copiar de un
          mapa (clic derecho → coordenadas).
        </p>
      </header>

      <label class="field">
        Nombre
        <input type="text" name="name" [(ngModel)]="form.name" required maxlength="120" />
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
            placeholder="40.1985"
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
            placeholder="-3.6560"
          />
        </label>
      </div>

      @if (error()) {
        <div class="alert" role="alert">{{ error() }}</div>
      }
      @if (saved()) {
        <div class="alert success" role="status">Guardado.</div>
      }

      <div class="actions">
        <button type="submit" class="btn btn-primary" [disabled]="busy() || f.invalid">
          {{ busy() ? 'Guardando…' : editingId() ? 'Guardar cambios' : 'Crear almacén' }}
        </button>
        @if (editingId()) {
          <button type="button" class="btn" (click)="startNew()">Nuevo almacén</button>
        }
      </div>
    </form>
  `,
  styleUrl: './data-forms.css',
})
export class WarehouseTabComponent {
  private readonly api = inject(ApiService);
  private readonly store = inject(PlanStore);

  protected readonly form = { name: '', address: '', lat: '', lon: '' };
  protected readonly editingId = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly saved = signal(false);

  constructor() {
    // Mientras no se haya pulsado "Nuevo almacén", el formulario edita el almacén elegido.
    effect(() => {
      const warehouse = this.store.warehouse();
      untracked(() => {
        if (warehouse) {
          this.editingId.set(warehouse.id);
          Object.assign(this.form, {
            name: warehouse.name,
            address: warehouse.address,
            lat: String(warehouse.location.lat),
            lon: String(warehouse.location.lon),
          });
        }
      });
    });
  }

  protected startNew(): void {
    this.editingId.set(null);
    Object.assign(this.form, { name: '', address: '', lat: '', lon: '' });
    this.saved.set(false);
    this.error.set('');
  }

  protected async save(): Promise<void> {
    this.error.set('');
    this.saved.set(false);
    const location = toLocation(this.form.lat, this.form.lon);
    if (typeof location === 'string') {
      this.error.set(location);
      return;
    }
    const body = { name: this.form.name.trim(), address: this.form.address.trim(), location };
    this.busy.set(true);
    try {
      const id = this.editingId();
      if (id) {
        await firstValueFrom(this.api.updateWarehouse(id, body));
      } else {
        const created = await firstValueFrom(this.api.createWarehouse(body));
        this.editingId.set(created.id);
      }
      await this.store.load();
      this.saved.set(true);
    } catch (error) {
      const problem = describeError(error);
      this.error.set([problem.message, ...problem.details].join(' '));
    } finally {
      this.busy.set(false);
    }
  }
}
