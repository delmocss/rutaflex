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
import { hasWindow, windowLabel } from '../../core/domain';
import { Stop, Warehouse } from '../../core/models';

const TEAL = '#0e7490';
const AMBER = '#d97706';
const INK = '#0f172a';

/** Madrid sur: vista inicial hasta que haya datos. */
const DEFAULT_CENTER: L.LatLngExpression = [40.25, -3.7];

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
      <span><i class="dot teal"></i> Parada flexible</span>
      <span><i class="dot amber"></i> Con franja horaria</span>
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
      background: #dbeafe;
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
      background: rgb(255 255 255 / 92%);
      box-shadow: 0 1px 4px rgb(15 23 42 / 25%);
      font-size: 12px;
      color: #0f172a;
    }
    .dot {
      display: inline-block;
      width: 10px;
      height: 10px;
      margin-right: 6px;
      border-radius: 50%;
      border: 1.5px solid #fff;
      box-shadow: 0 0 0 1px rgb(15 23 42 / 30%);
    }
    .dot.teal {
      background: #0e7490;
    }
    .dot.amber {
      background: #d97706;
    }
    .dot.square {
      border-radius: 2px;
      background: #0f172a;
    }
  `,
})
export class RouteMapComponent implements OnDestroy {
  readonly warehouse = input<Warehouse | null>(null);
  readonly stops = input<readonly Stop[]>([]);
  readonly selectedStopId = input<string | null>(null);
  readonly stopSelected = output<string>();

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private readonly ready = signal(false);

  private map?: L.Map;
  private resizeObserver?: ResizeObserver;
  private readonly layer = L.layerGroup();
  private readonly markers = new Map<string, L.CircleMarker>();
  private selectedMarker?: L.CircleMarker;
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

    // Redibuja cuando cambian los datos.
    effect(() => {
      if (!this.ready()) {
        return;
      }
      this.draw(this.warehouse(), this.stops());
      // draw() rehace todos los marcadores: se vuelve a resaltar la parada elegida (sin depender de ella aqui).
      untracked(() => this.highlight(this.selectedStopId()));
    });

    // Resalta la parada elegida.
    effect(() => {
      if (!this.ready()) {
        return;
      }
      this.highlight(this.selectedStopId());
    });
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.map?.remove();
  }

  private draw(warehouse: Warehouse | null, stops: readonly Stop[]): void {
    this.layer.clearLayers();
    this.markers.clear();
    this.selectedMarker = undefined;
    this.userAdjusted = false;

    const points: L.LatLngTuple[] = [];

    for (const stop of stops) {
      const position: L.LatLngTuple = [stop.location.lat, stop.location.lon];
      points.push(position);

      const marker = L.circleMarker(position, this.style(stop, false));
      marker.bindTooltip(this.tooltip(stop), { direction: 'top', offset: [0, -4] });
      marker.on('click', () => this.stopSelected.emit(stop.id));
      marker.addTo(this.layer);
      this.markers.set(stop.id, marker);
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
          {
            direction: 'top',
            offset: [0, -12],
          },
        )
        .addTo(this.layer);
    }

    this.bounds = points.length > 0 ? L.latLngBounds(points) : undefined;
    this.map?.invalidateSize({ animate: false });
    this.fit();
  }

  /** Encuadra todos los puntos dibujados. */
  private fit(): void {
    if (this.bounds) {
      this.map?.fitBounds(this.bounds, { padding: [40, 40], maxZoom: 14, animate: false });
    }
  }

  private highlight(id: string | null): void {
    this.selectedMarker?.setStyle({ weight: 1.5, color: '#ffffff' });
    this.selectedMarker?.closeTooltip();
    this.selectedMarker = undefined;

    const marker = id ? this.markers.get(id) : undefined;
    if (!marker || !this.map) {
      return;
    }
    marker.setStyle({ weight: 4, color: INK });
    marker.bringToFront();
    marker.openTooltip();
    if (!this.map.getBounds().contains(marker.getLatLng())) {
      this.map.panTo(marker.getLatLng());
    }
    this.selectedMarker = marker;
  }

  private style(stop: Stop, selected: boolean): L.CircleMarkerOptions {
    return {
      radius: 5 + Math.min(stop.parcels, 6) * 1.2,
      color: selected ? INK : '#ffffff',
      weight: selected ? 4 : 1.5,
      fillColor: hasWindow(stop) ? AMBER : TEAL,
      fillOpacity: 0.9,
    };
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
