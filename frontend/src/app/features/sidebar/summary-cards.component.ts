import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PlanSummary } from '../../core/domain';

@Component({
  selector: 'app-summary-cards',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let s = summary();
    <div class="cards">
      <div class="card">
        <span class="label">Paradas</span>
        <strong>{{ s.stops }}</strong>
      </div>
      <div class="card">
        <span class="label">Con franja</span>
        <strong>{{ s.withWindow }}</strong>
      </div>
      <div class="card wide" [class.warn]="s.overCapacity">
        <span class="label">Bultos / capacidad de la flota</span>
        <strong>
          {{ s.parcels }} <small>/ {{ s.capacity }}</small>
          @if (s.capacity > 0) {
            <small class="pct">{{ s.utilization }} %</small>
          }
        </strong>
        <div
          class="bar"
          role="progressbar"
          aria-label="Ocupación de la flota"
          [attr.aria-valuenow]="s.utilization"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <span [style.width.%]="s.utilization > 100 ? 100 : s.utilization"></span>
        </div>
        @if (s.overCapacity) {
          <span class="alert">Hay más bultos que capacidad: no habrá solución factible.</span>
        }
      </div>
    </div>
  `,
  styles: `
    .cards {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
    }
    .card {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 12px;
      border: 1px solid var(--line);
      border-radius: var(--radius);
      background: #f8fafc;
    }
    .card.wide {
      grid-column: 1 / -1;
    }
    .label {
      font-size: 12px;
      color: var(--muted);
    }
    strong {
      font-size: 22px;
      line-height: 1.2;
    }
    small {
      font-size: 13px;
      font-weight: 500;
      color: var(--muted);
    }
    .pct {
      margin-left: 6px;
      color: var(--brand-ink);
    }
    .bar {
      height: 6px;
      margin-top: 4px;
      border-radius: 3px;
      background: var(--line);
      overflow: hidden;
    }
    .bar span {
      display: block;
      height: 100%;
      background: var(--brand);
    }
    .warn {
      border-color: #fca5a5;
      background: #fef2f2;
    }
    .warn .bar span {
      background: var(--danger);
    }
    .alert {
      margin-top: 4px;
      font-size: 12px;
      color: var(--danger);
    }
  `,
})
export class SummaryCardsComponent {
  readonly summary = input.required<PlanSummary>();
}
