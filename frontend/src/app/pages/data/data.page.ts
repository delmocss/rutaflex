import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { StopsTabComponent } from './stops-tab.component';
import { VehiclesTabComponent } from './vehicles-tab.component';
import { WarehouseTabComponent } from './warehouse-tab.component';

type Tab = 'warehouse' | 'vehicles' | 'stops';

/** Gestión de los datos de partida: almacén, flota y paradas de cada día. */
@Component({
  selector: 'app-data-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WarehouseTabComponent, VehiclesTabComponent, StopsTabComponent],
  template: `
    <main>
      <header>
        <h1>Datos</h1>
        <p class="hint">
          Lo que RutaFlex necesita para planificar: dónde salen las furgonetas, cuáles son y qué hay
          que entregar.
        </p>
      </header>

      <div class="tabs" role="tablist" aria-label="Datos">
        @for (item of tabs; track item.id) {
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === item.id"
            [class.active]="tab() === item.id"
            (click)="tab.set(item.id)"
          >
            {{ item.label }}
          </button>
        }
      </div>

      <section role="tabpanel">
        @switch (tab()) {
          @case ('warehouse') {
            <app-warehouse-tab />
          }
          @case ('vehicles') {
            <app-vehicles-tab />
          }
          @case ('stops') {
            <app-stops-tab />
          }
        }
      </section>
    </main>
  `,
  styles: `
    :host {
      display: block;
      flex: 1;
    }
    main {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 18px;
      max-width: 1180px;
      margin: 0 auto;
      padding: 24px 20px 48px;
    }
    h1 {
      font-size: 22px;
      letter-spacing: -0.01em;
    }
    .hint {
      color: var(--muted);
      font-size: 14px;
    }
    .tabs {
      display: flex;
      gap: 4px;
      border-bottom: 1px solid var(--line-strong);
    }
    .tabs button {
      margin-bottom: -1px;
      padding: 9px 18px;
      border: 1px solid transparent;
      border-radius: var(--radius-sm) var(--radius-sm) 0 0;
      background: transparent;
      color: var(--muted);
      font: inherit;
      font-weight: 500;
      cursor: pointer;
    }
    .tabs button:hover {
      color: var(--ink);
    }
    .tabs button.active {
      border-color: var(--line-strong);
      border-bottom-color: var(--bg);
      background: var(--bg);
      color: var(--brand-ink);
    }
  `,
})
export class DataPage {
  protected readonly tab = signal<Tab>('stops');

  protected readonly tabs: { id: Tab; label: string }[] = [
    { id: 'stops', label: 'Paradas' },
    { id: 'vehicles', label: 'Furgonetas' },
    { id: 'warehouse', label: 'Almacén' },
  ];
}
