import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { translateFailureReason } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import {
  STATUS_LABEL,
  buildMapRoutes,
  formatDateTime,
  formatDay,
  formatKm,
  formatSeconds,
  isStale,
  savedKm,
  savedPercent,
} from '../../core/domain';
import { JobsStore } from '../../core/jobs.store';
import { OptimizationJob, Stop, Vehicle } from '../../core/models';
import { PlanStore } from '../../core/plan.store';
import { RouteMapComponent } from '../../features/map/route-map.component';

/** Historial de optimizaciones y detalle de cada una: comparación de km y recorrido de cada furgoneta. */
@Component({
  selector: 'app-results-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouteMapComponent],
  templateUrl: './results.page.html',
  styleUrl: './results.page.css',
})
export class ResultsPage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly plan = inject(PlanStore);
  protected readonly jobs = inject(JobsStore);

  protected readonly statusLabel = STATUS_LABEL;
  protected readonly km = formatKm;
  protected readonly seconds = formatSeconds;
  protected readonly day = formatDay;
  protected readonly when = formatDateTime;
  protected readonly failure = translateFailureReason;
  protected readonly saved = savedKm;
  protected readonly savedPct = savedPercent;

  /** Datos del dia del trabajo mostrado, para poner nombre a las furgonetas y direcciones a las paradas. */
  private readonly stops = signal<Stop[]>([]);
  private readonly vehicles = signal<Vehicle[]>([]);
  protected readonly contextError = signal(false);
  protected readonly activeRoute = signal<string | null>(null);
  private contextOrder = 0;

  protected readonly warehouse = computed(
    () => this.plan.warehouses().find((w) => w.id === this.jobs.current()?.warehouseId) ?? null,
  );

  protected readonly stale = computed(() => isStale(this.jobs.current(), this.stops()));

  protected readonly routes = computed(() =>
    this.stale()
      ? []
      : buildMapRoutes(this.jobs.current(), this.vehicles(), this.stops(), this.warehouse()),
  );

  protected readonly vehiclesUsed = computed(() => this.routes().length);

  constructor() {
    // Al elegir otro trabajo se cargan los datos de su día y su almacén.
    effect(() => {
      const job = this.jobs.current();
      untracked(() => {
        this.activeRoute.set(null);
        void this.loadContext(job);
      });
    });
  }

  async ngOnInit(): Promise<void> {
    await this.jobs.refreshList();
    // Abre el más reciente para que la pantalla no empiece vacía.
    const first = this.jobs.jobs()[0];
    if (first && !this.jobs.current()) {
      await this.jobs.open(first.id);
    }
  }

  ngOnDestroy(): void {
    this.jobs.stop();
  }

  protected open(job: OptimizationJob): void {
    void this.jobs.open(job.id);
  }

  protected toggleRoute(id: string): void {
    this.activeRoute.update((current) => (current === id ? null : id));
  }

  private async loadContext(job: OptimizationJob | null): Promise<void> {
    const order = ++this.contextOrder;
    this.contextError.set(false);
    if (!job || job.status !== 'COMPLETED') {
      this.stops.set([]);
      this.vehicles.set([]);
      return;
    }
    try {
      const [stops, vehicles] = await Promise.all([
        firstValueFrom(this.api.stops(job.deliveryDate)),
        firstValueFrom(this.api.vehicles(job.warehouseId)),
      ]);
      if (order === this.contextOrder) {
        this.stops.set(stops);
        this.vehicles.set(vehicles);
      }
    } catch {
      if (order === this.contextOrder) {
        this.contextError.set(true);
      }
    }
  }
}
