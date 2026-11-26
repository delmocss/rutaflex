import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { translateFailureReason } from '../../core/api-error';
import {
  STATUS_LABEL,
  buildMapRoutes,
  dayOptionLabel,
  formatKm,
  formatSeconds,
  isStale,
  savedKm,
  savedPercent,
} from '../../core/domain';
import { JobsStore } from '../../core/jobs.store';
import { PlanStore } from '../../core/plan.store';
import { RouteMapComponent } from '../../features/map/route-map.component';
import { FleetListComponent } from '../../features/sidebar/fleet-list.component';
import { ImportPanelComponent } from '../../features/sidebar/import-panel.component';
import { StopListComponent } from '../../features/sidebar/stop-list.component';
import { SummaryCardsComponent } from '../../features/sidebar/summary-cards.component';

/** Planificación: elegir un día, optimizarlo y ver las rutas sobre el mapa. */
@Component({
  selector: 'app-planning-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    RouteMapComponent,
    SummaryCardsComponent,
    FleetListComponent,
    StopListComponent,
    ImportPanelComponent,
  ],
  templateUrl: './planning.page.html',
  styleUrl: './planning.page.css',
})
export class PlanningPage implements OnDestroy {
  protected readonly store = inject(PlanStore);
  protected readonly jobs = inject(JobsStore);

  protected readonly dayLabel = dayOptionLabel;
  protected readonly statusLabel = STATUS_LABEL;
  protected readonly km = formatKm;
  protected readonly seconds = formatSeconds;
  protected readonly failure = translateFailureReason;
  protected readonly saved = savedKm;
  protected readonly savedPct = savedPercent;

  /** Ruta resaltada en el mapa (id de furgoneta), elegida desde su tarjeta. */
  protected readonly activeRoute = signal<string | null>(null);

  /** El trabajo mostrado es de este día y completado. */
  private readonly completed = computed(() => {
    const job = this.jobs.current();
    return job && job.status === 'COMPLETED' && job.deliveryDate === this.store.selectedDate()
      ? job
      : null;
  });

  /** Los datos del día cambiaron desde el cálculo: sus rutas ya no valen. */
  protected readonly stale = computed(() => isStale(this.completed(), this.store.stops()));

  protected readonly routes = computed(() =>
    this.stale()
      ? []
      : buildMapRoutes(
          this.completed(),
          this.store.vehicles(),
          this.store.stops(),
          this.store.warehouse(),
        ),
  );

  protected readonly canOptimise = computed(() => {
    const summary = this.store.summary();
    return (
      summary.stops > 0 &&
      summary.vehicles > 0 &&
      !summary.overCapacity &&
      !this.jobs.active() &&
      !this.jobs.requesting()
    );
  });

  constructor() {
    // Al cambiar de día se muestra la última optimización de ese día (o ninguna).
    effect(() => {
      const date = this.store.selectedDate();
      untracked(() => {
        this.activeRoute.set(null);
        if (date) {
          void this.jobs.openLatestFor(date);
        } else {
          this.jobs.stop();
        }
      });
    });
  }

  ngOnDestroy(): void {
    this.jobs.stop();
  }

  protected optimise(): void {
    const date = this.store.selectedDate();
    if (date) {
      this.activeRoute.set(null);
      void this.jobs.request(date, this.store.warehouse()?.id);
    }
  }

  protected toggleRoute(id: string): void {
    this.activeRoute.update((current) => (current === id ? null : id));
  }

  protected onDay(event: Event): void {
    void this.store.selectDate((event.target as HTMLSelectElement).value);
  }

  protected onWarehouse(event: Event): void {
    void this.store.selectWarehouse((event.target as HTMLSelectElement).value);
  }
}
