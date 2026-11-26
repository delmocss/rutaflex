import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  effect,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { MapRoute, hasWindow, windowLabel } from '../../core/domain';
import { Stop, Warehouse } from '../../core/models';

const OLIVE = '#5f6f3a';
const OCHRE = '#b3822f';
const INK = '#2a251d';

/** Madrid sur: vista inicial hasta que haya datos. */
const DEFAULT_CENTER: L.LatLngExpression = [40.25, -3.7];

/**
 * Mapa de paradas. Sin rutas, las paradas se colorean por tener franja horaria o no. Con rutas, cada parada
 * toma el color de su furgoneta, lleva su numero de orden y se dibuja el trazado (en linea recta entre
 * paradas: no hay red viaria, asi que es una aproximacion, no el recorrido por calles).
 */
@Component({
  selector: 'app-route-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      #host
      class="map"
      role="region"
      aria-label="Mapa de paradas de reparto (los mismos datos están en la lista de paradas)"
    ></div>
    <div class="legend" aria-hidden="true">
      @if (routes().length === 0) {
        <span><i class="dot olive"></i> Parada flexible</span>
        <span><i class="dot ochre"></i> Con franja horaria</span>
      } @else {
        @for (route of routes(); track route.id) {
          <span><i class="dot" [style.background]="route.color"></i> {{ route.name }}</span>
        }
        <span><i class="dot ring"></i> Con franja horaria</span>
      }
      <span><i class="dot square"></i> Almacén</span>
    </div>
  `,
  styles: `
    :host {
      position: relative;
      display: block;
      height: 100%;
    }
    .map {
      height: 100%;
      width: 100%;
      background: #e8e4d8;
    }
    .legend {
      position: absolute;
      left: 12px;
      bottom: 24px;
      z-index: 500;
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 8px 10px;
      border-radius: 8px;
      background: rgb(255 253 249 / 94%);
      box-shadow: 0 1px 4px rgb(42 37 29 / 25%);
      font-size: 12px;
      color: var(--ink);
    }
    .dot {
      display: inline-block;
      width: 10px;
      height: 10px;
      margin-right: 6px;
      border-radius: 50%;
      border: 1.5px solid #fff;
      box-shadow: 0 0 0 1px rgb(42 37 29 / 30%);
    }
    .dot.olive {
      background: #5f6f3a;
    }
    .dot.ochre {
      background: #b3822f;
    }
    .dot.ring {
      background: #fff;
      border-color: #2a251d;
      box-shadow: none;
    }
    .dot.square {
      border-radius: 2px;
      background: #2a251d;
    }
  `,
})
export class RouteMapComponent implements OnDestroy {
  readonly warehouse = input<Warehouse | null>(null);
  readonly stops = input<readonly Stop[]>([]);
  readonly routes = input<readonly MapRoute[]>([]);
  /** Ruta resaltada (id de furgoneta); las demas se atenuan. */
  readonly activeRouteId = input<string | null>(null);
  readonly selectedStopId = input<string | null>(null);
  readonly stopSelected = output<string>();

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private readonly ready = signal(false);

  private map?: L.Map;
  private resizeObserver?: ResizeObserver;
  private readonly layer = L.layerGroup();
  private readonly markers = new Map<string, L.CircleMarker>();
  private readonly lines = new Map<string, L.Polyline>();
  private selectedMarker?: L.CircleMarker;
  /** Borde que tenia la parada antes de resaltarla, para devolverselo (las de franja llevan borde oscuro). */
  private selectedBorder?: { weight: number; color: string };
  private bounds?: L.LatLngBounds;
  /** El usuario ha movido o ampliado el mapa: no se le reencuadra por un cambio de tamano. */
  private userAdjusted = false;

  constructor() {
    afterNextRender(() => {
      this.map = L.map(this.host().nativeElement, {
        center: DEFAULT_CENTER,
        zoom: 11,
        zoomControl: true,
      });
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).addTo(this.map);
      this.layer.addTo(this.map);

      // Leaflet calcula el encuadre con el tamano del contenedor: si cambia (layout, ventana, panel
      // movil) hay que avisarle, o el zoom y los limites quedan calculados para un tamano viejo.
      const map = this.map;
      this.resizeObserver = new ResizeObserver(() => {
        map.invalidateSize({ animate: false });
        if (!this.userAdjusted) {
          this.fit();
        }
      });
      this.resizeObserver.observe(this.host().nativeElement);

      const container = this.host().nativeElement;
      for (const type of ['pointerdown', 'wheel', 'keydown']) {
        container.addEventListener(type, () => (this.userAdjusted = true), { passive: true });
      }

      this.ready.set(true);
    });

    // Redibuja cuando cambian los datos o las rutas.
    effect(() => {
      if (!this.ready()) {
        return;
      }
      this.draw(this.warehouse(), this.stops(), this.routes());
      // draw() rehace todos los marcadores: se vuelve a resaltar lo elegido (sin depender de ello aqui).
      untracked(() => {
        this.emphasise(this.activeRouteId());
        this.highlight(this.selectedStopId());
      });
    });

    effect(() => {
      if (this.ready()) {
        this.emphasise(this.activeRouteId());
      }
    });

    effect(() => {
      if (this.ready()) {
        this.highlight(this.selectedStopId());
      }
    });
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.map?.remove();
  }

  private draw(
    warehouse: Warehouse | null,
    stops: readonly Stop[],
    routes: readonly MapRoute[],
  ): void {
    this.layer.clearLayers();
    this.markers.clear();
    this.lines.clear();
    this.selectedMarker = undefined;
    this.userAdjusted = false;

    const points: L.LatLngTuple[] = [];

    if (routes.length > 0) {
      this.drawRoutes(routes, points);
    } else {
      for (const stop of stops) {
        const position: L.LatLngTuple = [stop.location.lat, stop.location.lon];
        points.push(position);
        const marker = L.circleMarker(position, {
          radius: 5 + Math.min(stop.parcels, 6) * 1.2,
          color: '#ffffff',
          weight: 1.5,
          fillColor: hasWindow(stop) ? OCHRE : OLIVE,
          fillOpacity: 0.9,
        });
        marker.bindTooltip(this.tooltip(stop), { direction: 'top', offset: [0, -4] });
        marker.on('click', () => this.stopSelected.emit(stop.id));
        marker.addTo(this.layer);
        this.markers.set(stop.id, marker);
      }
    }

    if (warehouse) {
      const position: L.LatLngTuple = [warehouse.location.lat, warehouse.location.lon];
      points.push(position);
      L.marker(position, {
        icon: L.divIcon({
          className: 'warehouse-icon',
          html: `<div style="width:26px;height:26px;border-radius:6px;background:${INK};border:2px solid #fff;box-shadow:0 1px 5px rgb(0 0 0 / 45%);display:flex;align-items:center;justify-content:center"><svg width="14" height="14" viewBox="0 0 24 24" fill="#fff" aria-hidden="true"><path d="M12 3 2 9v12h6v-7h8v7h6V9z"/></svg></div>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        zIndexOffset: 1000,
        title: warehouse.name,
      })
        .bindTooltip(
          `<strong>${this.escape(warehouse.name)}</strong><br>${this.escape(warehouse.address)}`,
          { direction: 'top', offset: [0, -12] },
        )
        .addTo(this.layer);
    }

    this.bounds = points.length > 0 ? L.latLngBounds(points) : undefined;
    this.map?.invalidateSize({ animate: false });
    this.fit();
  }

  private drawRoutes(routes: readonly MapRoute[], points: L.LatLngTuple[]): void {
    const windowIds = new Set(
      this.stops()
        .filter((stop) => hasWindow(stop))
        .map((stop) => stop.id),
    );
    for (const route of routes) {
      const path: L.LatLngTuple[] = [
        [route.depot.lat, route.depot.lon],
        ...route.stops.map((stop): L.LatLngTuple => [stop.location.lat, stop.location.lon]),
        [route.depot.lat, route.depot.lon],
      ];
      const line = L.polyline(path, { color: route.color, weight: 4, opacity: 0.85 }).addTo(
        this.layer,
      );
      this.lines.set(route.id, line);
      points.push(...path);

      for (const stop of route.stops) {
        const position: L.LatLngTuple = [stop.location.lat, stop.location.lon];
        const marker = L.circleMarker(position, {
          radius: 10,
          color: windowIds.has(stop.id) ? INK : '#ffffff',
          weight: windowIds.has(stop.id) ? 3 : 1.5,
          fillColor: route.color,
          fillOpacity: 1,
        });
        marker.bindTooltip(String(stop.sequence), {
          permanent: true,
          direction: 'center',
          className: 'seq-label',
        });
        marker.bindPopup(
          `<strong>${this.escape(stop.address)}</strong><br>${this.escape(route.name)} · parada ${stop.sequence}` +
            `<br>${stop.parcels} ${stop.parcels === 1 ? 'bulto' : 'bultos'} · llegada ${this.escape(stop.arrival)}`,
        );
        marker.on('click', () => this.stopSelected.emit(stop.id));
        marker.addTo(this.layer);
        this.markers.set(stop.id, marker);
      }
    }
  }

  /** Atenua las rutas que no son la elegida. Sin ruta elegida, todas se ven igual. */
  private emphasise(activeId: string | null): void {
    for (const [id, line] of this.lines) {
      const dimmed = activeId !== null && id !== activeId;
      line.setStyle({ opacity: dimmed ? 0.2 : 0.85, weight: !dimmed && activeId ? 6 : 4 });
    }
  }

  /** Encuadra todos los puntos dibujados. */
  private fit(): void {
    if (this.bounds) {
      this.map?.fitBounds(this.bounds, { padding: [40, 40], maxZoom: 14, animate: false });
    }
  }

  private highlight(id: string | null): void {
    if (this.selectedMarker && this.selectedBorder) {
      this.selectedMarker.setStyle(this.selectedBorder);
    }
    this.selectedMarker?.closeTooltip();
    this.selectedMarker?.closePopup();
    this.selectedMarker = undefined;

    const marker = id ? this.markers.get(id) : undefined;
    if (!marker || !this.map) {
      return;
    }
    this.selectedBorder = {
      weight: marker.options.weight ?? 1.5,
      color: marker.options.color ?? '#ffffff',
    };
    marker.setStyle({ weight: 4, color: INK });
    marker.bringToFront();
    if (this.routes().length > 0) {
      marker.openPopup();
    } else {
      marker.openTooltip();
    }
    if (!this.map.getBounds().contains(marker.getLatLng())) {
      this.map.panTo(marker.getLatLng());
    }
    this.selectedMarker = marker;
  }

  private tooltip(stop: Stop): string {
    const window = windowLabel(stop);
    const bundles = `${stop.parcels} ${stop.parcels === 1 ? 'bulto' : 'bultos'}`;
    return `<strong>${this.escape(stop.address)}</strong><br>${bundles}${window ? ` · franja ${window}` : ' · sin franja'}`;
  }

  /** Los textos vienen de la base de datos (CSV de usuarios): nunca se inyectan como HTML sin escapar. */
  private escape(text: string): string {
    return text.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);
  }
}
