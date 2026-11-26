import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ApiProblem, describeError } from '../../core/api-error';
import { PlanStore } from '../../core/plan.store';

function today(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

@Component({
  selector: 'app-import-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form (submit)="submit($event)">
      <p class="help">
        CSV UTF-8 con cabecera <code>address,lat,lon,parcels</code> y, opcionalmente,
        <code>window_start,window_end</code> (HH:mm).
      </p>

      <label>
        Fichero CSV
        <input #fileInput type="file" accept=".csv,text/csv" (change)="onFile($event)" />
      </label>

      <label>
        Día de reparto
        <input type="date" [value]="date()" (input)="onDate($event)" />
      </label>

      <label class="check">
        <input
          type="checkbox"
          [checked]="replace()"
          (change)="replace.set($any($event.target).checked)"
        />
        Sustituir las paradas de ese día
      </label>
      @if (replace() && existing() > 0) {
        <p class="warning" role="status">
          Se borrarán las {{ existing() }} paradas que ya hay ese día y se sustituirán por las del
          fichero.
        </p>
      }

      <button type="submit" [disabled]="!canSubmit()">
        {{ busy() ? 'Importando…' : 'Importar paradas' }}
      </button>

      <div class="feedback" aria-live="polite">
        @if (success(); as message) {
          <p class="ok">{{ message }}</p>
        }
        @if (problem(); as p) {
          <p class="error">{{ p.message }}</p>
          @if (p.details.length > 0) {
            <ul>
              @for (detail of p.details; track $index) {
                <li>{{ detail }}</li>
              }
            </ul>
          }
        }
      </div>
    </form>
  `,
  styles: `
    form {
      display: grid;
      gap: 10px;
      font-size: 13px;
    }
    label {
      display: grid;
      gap: 4px;
      font-weight: 500;
    }
    label.check {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 400;
    }
    input[type='date'],
    input[type='file'] {
      font: inherit;
      padding: 6px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #fff;
    }
    .help {
      margin: 0;
      color: var(--muted);
    }
    code {
      padding: 0 4px;
      border-radius: 4px;
      background: var(--sunken);
      font-size: 12px;
    }
    button {
      padding: 8px 12px;
      border: 0;
      border-radius: 8px;
      background: var(--brand);
      color: #fff;
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    button:disabled {
      background: var(--line-strong);
      cursor: not-allowed;
    }
    button:focus-visible,
    input:focus-visible {
      outline: 2px solid var(--brand);
      outline-offset: 2px;
    }
    .ok {
      margin: 0;
      color: #335c3c;
    }
    .error {
      margin: 0;
      color: var(--danger);
      font-weight: 600;
    }
    .warning {
      margin: 0;
      padding: 6px 8px;
      border-radius: 6px;
      background: var(--accent-soft);
      color: #7b5718;
    }
    ul {
      max-height: 160px;
      margin: 4px 0 0;
      padding-left: 18px;
      overflow: auto;
      color: var(--danger);
      font-size: 12px;
    }
  `,
})
export class ImportPanelComponent {
  private readonly store = inject(PlanStore);

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');
  private dateTouched = false;

  protected readonly file = signal<File | null>(null);
  protected readonly date = signal(this.store.selectedDate() ?? today());
  /** Por defecto no se borra nada: sustituir es una decision explicita del usuario. */
  protected readonly replace = signal(false);
  /** Paradas que ya hay en el dia elegido (lo que borraria "sustituir"). */
  protected readonly existing = computed(
    () => this.store.days().find((day) => day.date === this.date())?.stops ?? 0,
  );
  protected readonly busy = signal(false);
  protected readonly success = signal<string | null>(null);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly canSubmit = computed(() => !!this.file() && !!this.date() && !this.busy());

  constructor() {
    // El dia de destino sigue al dia que se esta viendo, mientras el usuario no lo cambie a mano.
    effect(() => {
      const selected = this.store.selectedDate();
      if (selected && !this.dateTouched) {
        this.date.set(selected);
      }
    });
  }

  protected onDate(event: Event): void {
    this.dateTouched = true;
    this.date.set((event.target as HTMLInputElement).value);
  }

  protected onFile(event: Event): void {
    this.file.set((event.target as HTMLInputElement).files?.item(0) ?? null);
    this.success.set(null);
    this.problem.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    const file = this.file();
    if (!file || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.success.set(null);
    this.problem.set(null);
    try {
      const { result, reloaded } = await this.store.importCsv(this.date(), file, this.replace());
      this.success.set(
        `${result.imported} paradas importadas` +
          (result.replaced > 0 ? ` (sustituyen a ${result.replaced})` : '') +
          '.' +
          (reloaded ? '' : ' No se ha podido recargar la vista: pulsa «Recargar».'),
      );
      // Se vacia el formulario: sin esto, otro clic o un Intro repetirian la subida y duplicarian las paradas.
      this.file.set(null);
      this.fileInput().nativeElement.value = '';
    } catch (error) {
      this.problem.set(describeError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
