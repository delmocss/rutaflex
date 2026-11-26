import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Portada: qué es RutaFlex, cómo funciona y con qué está hecho. Contenido estático. */
@Component({
  selector: 'app-landing-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './landing.page.html',
  styleUrl: './landing.page.css',
})
export class LandingPage {
  protected readonly repository = 'https://github.com/delmocss/rutaflex';

  protected readonly steps = [
    {
      title: 'Carga las paradas',
      text: 'Importa un CSV o añádelas a mano: dirección, coordenadas, bultos y, si la hay, la franja horaria de entrega.',
    },
    {
      title: 'Optimiza el día',
      text: 'El motor reparte las paradas entre las furgonetas y ordena cada ruta. El cálculo es asíncrono: lo lanzas y sigues trabajando.',
    },
    {
      title: 'Revisa el resultado',
      text: 'Cada furgoneta con su color sobre el mapa, el orden de paradas, la hora estimada y los kilómetros frente al reparto sin ordenar.',
    },
  ] as const;

  protected readonly rules = [
    { name: 'Capacidad', text: 'Ninguna furgoneta lleva más bultos de los que caben.' },
    {
      name: 'Franjas horarias',
      text: 'Cada parada se atiende dentro de su ventana; si se llega antes, se espera.',
    },
    { name: 'Fin de jornada', text: 'La furgoneta vuelve al almacén antes de que acabe su turno.' },
    {
      name: 'Distancia',
      text: 'Entre todas las soluciones válidas, se elige la que menos kilómetros recorre.',
    },
  ] as const;

  protected readonly stack = [
    'Angular',
    'Leaflet',
    'Spring Boot',
    'PostgreSQL + PostGIS',
    'Apache Kafka',
    'Timefold Solver',
    'Testcontainers',
  ] as const;
}
