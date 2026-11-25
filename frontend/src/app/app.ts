import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { dayOptionLabel } from './core/domain';
import { PlanStore } from './core/plan.store';
import { RouteMapComponent } from './features/map/route-map.component';
import { FleetListComponent } from './features/sidebar/fleet-list.component';
import { ImportPanelComponent } from './features/sidebar/import-panel.component';
import { StopListComponent } from './features/sidebar/stop-list.component';
import { SummaryCardsComponent } from './features/sidebar/summary-cards.component';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouteMapComponent,
    SummaryCardsComponent,
    FleetListComponent,
    StopListComponent,
    ImportPanelComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly store = inject(PlanStore);
  protected readonly dayLabel = dayOptionLabel;

  ngOnInit(): void {
    void this.store.load();
  }

  protected onDay(event: Event): void {
    void this.store.selectDate((event.target as HTMLSelectElement).value);
  }

  protected onWarehouse(event: Event): void {
    void this.store.selectWarehouse((event.target as HTMLSelectElement).value);
  }
}
