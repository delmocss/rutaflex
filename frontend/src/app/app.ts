import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PlanStore } from './core/plan.store';

/** Armazon comun: la barra de navegacion y la pantalla activa. Los datos se cargan una vez para todas. */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly store = inject(PlanStore);

  ngOnInit(): void {
    void this.store.load();
  }
}
