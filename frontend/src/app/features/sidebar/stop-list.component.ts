import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { windowLabel } from '../../core/domain';
import { Stop } from '../../core/models';

@Component({
  selector: 'app-stop-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (stops().length === 0) {
      <p class="empty">No hay paradas para este día.</p>
    } @else {
      <ol>
        @for (stop of stops(); track stop.id; let i = $index) {
          <li>
            <button
              type="button"
              [class.selected]="stop.id === selectedId()"
              [attr.aria-pressed]="stop.id === selectedId()"
              (click)="selected.emit(stop.id)"
            >
              <span class="num">{{ i + 1 }}</span>
              <span class="address">{{ stop.address }}</span>
              <span class="chips">
                <span class="chip"
                  >{{ stop.parcels }} {{ stop.parcels === 1 ? 'bulto' : 'bultos' }}</span
                >
                @if (label(stop); as window) {
                  <span class="chip window" title="Franja horaria">{{ window }}</span>
                }
              </span>
            </button>
          </li>
        }
      </ol>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    ol {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li + li {
      border-top: 1px solid var(--line);
    }
    button {
      display: grid;
      grid-template-columns: 28px 1fr;
      grid-template-areas: 'num address' '. chips';
      gap: 2px 8px;
      width: 100%;
      padding: 8px 10px;
      border: 0;
      border-left: 3px solid transparent;
      background: transparent;
      text-align: left;
      font: inherit;
      color: inherit;
      cursor: pointer;
    }
    button:hover {
      background: #f1f5f9;
    }
    button:focus-visible {
      outline: 2px solid var(--brand);
      outline-offset: -2px;
    }
    button.selected {
      border-left-color: var(--brand);
      background: #ecfeff;
    }
    .num {
      grid-area: num;
      font-size: 12px;
      color: var(--muted);
      font-variant-numeric: tabular-nums;
    }
    .address {
      grid-area: address;
      font-size: 13px;
      font-weight: 500;
    }
    .chips {
      grid-area: chips;
      display: flex;
      gap: 6px;
    }
    .chip {
      padding: 1px 7px;
      border-radius: 999px;
      background: #e2e8f0;
      font-size: 11px;
      color: #334155;
    }
    .chip.window {
      background: #fef3c7;
      color: #92400e;
    }
    .empty {
      margin: 0;
      padding: 12px 10px;
      font-size: 13px;
      color: var(--muted);
    }
  `,
})
export class StopListComponent {
  readonly stops = input<readonly Stop[]>([]);
  readonly selectedId = input<string | null>(null);
  readonly selected = output<string>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    // Al elegir una parada desde el mapa, la lista se desplaza hasta ella.
    effect(() => {
      if (this.selectedId()) {
        queueMicrotask(() =>
          this.host.nativeElement
            .querySelector('button.selected')
            ?.scrollIntoView({ block: 'nearest' }),
        );
      }
    });
  }

  protected label(stop: Stop): string | null {
    return windowLabel(stop);
  }
}
