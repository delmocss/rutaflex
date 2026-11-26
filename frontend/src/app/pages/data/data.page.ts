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

      <div class="tabs" role="tablist" aria-label="Datos" (keydown)="onKey($event)">
        @for (item of tabs; track item.id) {
          <button
            type="button"
            role="tab"
            [id]="'tab-' + item.id"
            [attr.aria-selected]="tab() === item.id"
            [attr.aria-controls]="'panel-' + item.id"
            [attr.tabindex]="tab() === item.id ? 0 : -1"
            [class.active]="tab() === item.id"
            (click)="select(item.id)"
          >
            {{ item.label }}
          </button>
        }
      </div>

      <section role="tabpanel" [id]="'panel-' + tab()" [attr.aria-labelledby]="'tab-' + tab()">
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

  protected select(id: Tab, focus = false): void {
    this.tab.set(id);
    if (focus) {
      // El foco sigue a la pestaña elegida con el teclado (patrón ARIA de pestañas).
      queueMicrotask(() => document.getElementById('tab-' + id)?.focus());
    }
  }

  /** Flechas izquierda/derecha, Inicio y Fin cambian de pestaña, como en el patrón ARIA. */
  protected onKey(event: KeyboardEvent): void {
    const index = this.tabs.findIndex((item) => item.id === this.tab());
    let next = -1;
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % this.tabs.length;
        break;
      case 'ArrowLeft':
        next = (index - 1 + this.tabs.length) % this.tabs.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = this.tabs.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.select(this.tabs[next].id, true);
  }
}
