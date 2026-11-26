import { Routes } from '@angular/router';

/** Cada pantalla se carga cuando se visita (lazy): la portada no descarga el mapa ni los formularios. */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'RutaFlex · Rutas de reparto optimizadas',
    loadComponent: () => import('./pages/landing/landing.page').then((m) => m.LandingPage),
  },
  {
    path: 'planificacion',
    title: 'Planificación · RutaFlex',
    loadComponent: () => import('./pages/planning/planning.page').then((m) => m.PlanningPage),
  },
  {
    path: 'resultados',
    title: 'Resultados · RutaFlex',
    loadComponent: () => import('./pages/results/results.page').then((m) => m.ResultsPage),
  },
  {
    path: 'datos',
    title: 'Datos · RutaFlex',
    loadComponent: () => import('./pages/data/data.page').then((m) => m.DataPage),
  },
  { path: '**', redirectTo: '' },
];
