import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <h1 tabindex="-1">Page not found</h1>
      <p>There is no console page at this address. <a routerLink="/authorizations">Go to authorizations</a>.</p>
    </div>
  `,
})
export class NotFoundComponent {}
