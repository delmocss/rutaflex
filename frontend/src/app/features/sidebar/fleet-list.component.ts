import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { formatTime } from '../../core/domain';
import { Vehicle } from '../../core/models';

@Component({
  selector: 'app-fleet-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (vehicles().length === 0) {
      <p class="empty">Este almacén no tiene furgonetas.</p>
    } @else {
      <ul>
        @for (vehicle of vehicles(); track vehicle.id) {
          <li>
            <span class="name">{{ vehicle.name }}</span>
            <span class="meta">
              {{ vehicle.capacityParcels }} bultos · {{ time(vehicle.shiftStart) }}–{{
                time(vehicle.shiftEnd)
              }}
            </span>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    ul {
      margin: 0;
      padding: 0;
      list-style: none;
      display: grid;
      gap: 6px;
    }
    li {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      padding: 7px 10px;
      border: 1px solid var(--line);
      border-radius: 8px;
      font-size: 13px;
    }
    .name {
      font-weight: 600;
    }
    .meta {
      color: var(--muted);
    }
    .empty {
      margin: 0;
      font-size: 13px;
      color: var(--muted);
    }
  `,
})
export class FleetListComponent {
  readonly vehicles = input<readonly Vehicle[]>([]);

  protected time(value: string): string {
    return formatTime(value);
  }
}
