import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { APP_CONFIG } from './core/config/app-config';
import { ErrorBannerComponent } from './core/errors/error-banner.component';
import { WarmUpBannerComponent } from './core/warm-up/warm-up-banner.component';
import { WarmUpService } from './core/warm-up/warm-up.service';
import { AUTHOR_HANDLE, AUTHOR_URL, REPO_URL } from './demo/demo-links';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ErrorBannerComponent, WarmUpBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.css',
  template: `
    <a class="skip-link" href="#main" (click)="focusMain($event)">Skip to content</a>
    <header class="shell-header">
      <a class="brand" routerLink="/">cardrail <span class="brand__sub">console</span></a>
      <nav aria-label="Primary">
        <ul class="nav">
          <li>
            <a routerLink="/" routerLinkActive="is-active" [routerLinkActiveOptions]="{ exact: true }"
               ariaCurrentWhenActive="page">Overview</a>
          </li>
          <li><a routerLink="/terminal" routerLinkActive="is-active" ariaCurrentWhenActive="page">Terminal</a></li>
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
    <app-warm-up-banner />
    <app-error-banner />
    <main id="main" tabindex="-1">
      <router-outlet />
    </main>
    <footer class="shell-footer">
      <p>
        cardrail · <a [href]="repoUrl" rel="noopener">source on GitHub</a> · by
        <a [href]="authorUrl" rel="noopener">{{ authorHandle }}</a>
      </p>
      <p class="muted">Learning-grade model, not PCI DSS certified. Published test cards only.</p>
    </footer>
  `,
})
export class App {
  protected readonly devTools = inject(APP_CONFIG).devTools;
  protected readonly repoUrl = REPO_URL;
  protected readonly authorUrl = AUTHOR_URL;
  protected readonly authorHandle = AUTHOR_HANDLE;
  private readonly router = inject(Router);

  constructor() {
    // Start waking the free-tier backend straight away, without holding up the first page.
    inject(WarmUpService).start();

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
