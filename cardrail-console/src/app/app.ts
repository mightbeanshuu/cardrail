import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { APP_CONFIG } from './core/config/app-config';
import { ErrorBannerComponent } from './core/errors/error-banner.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.css',
  template: `
    <a class="skip-link" href="#main" (click)="focusMain($event)">Skip to content</a>
    <header class="shell-header">
      <a class="brand" routerLink="/authorizations">cardrail <span class="brand__sub">operations</span></a>
      <nav aria-label="Primary">
        <ul class="nav">
          <li><a routerLink="/authorizations" routerLinkActive="is-active" ariaCurrentWhenActive="page">Authorizations</a></li>
          <li><a routerLink="/audit" routerLinkActive="is-active" ariaCurrentWhenActive="page">Audit</a></li>
          <li><a routerLink="/settlement" routerLinkActive="is-active" ariaCurrentWhenActive="page">Settlement</a></li>
          @if (devTools) {
            <li>
              <a routerLink="/dev/issuer" routerLinkActive="is-active" ariaCurrentWhenActive="page">Issuer simulator</a>
            </li>
          }
        </ul>
      </nav>
    </header>
    <app-error-banner />
    <main id="main" tabindex="-1">
      <router-outlet />
    </main>
  `,
})
export class App {
  protected readonly devTools = inject(APP_CONFIG).devTools;
  private readonly router = inject(Router);

  constructor() {
    // Move focus to the new page's heading when the path changes (not on query-string updates
    // such as filtering or paging), so keyboard and screen reader users land on the new content.
    let lastPath = '';
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((e) => {
        const path = e.urlAfterRedirects.split(/[?#]/)[0];
        if (lastPath !== '' && path !== lastPath) {
          setTimeout(() => this.focusHeading());
        }
        lastPath = path;
      });
  }

  protected focusMain(event: Event): void {
    event.preventDefault();
    this.focusHeading();
  }

  private focusHeading(): void {
    const heading = document.querySelector<HTMLElement>('main h1');
    const target = heading ?? document.getElementById('main');
    if (target) {
      if (!target.hasAttribute('tabindex')) {
        target.setAttribute('tabindex', '-1');
      }
      target.focus();
    }
  }
}
