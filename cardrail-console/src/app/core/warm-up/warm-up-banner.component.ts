import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { WarmUpService } from './warm-up.service';

/** Calm, specific notice while the free-tier demo backend wakes up; never blocks the page. */
@Component({
  selector: 'app-warm-up-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (warmUp.failed()) {
      <div class="banner-stack">
        <div class="banner banner--error" role="alert">
          <div class="banner__body">
            <p class="banner__title">The demo backend did not wake up within two minutes.</p>
            <p class="banner__detail">
              It may be redeploying or over its free-tier hours. Pages that need it will show errors until it answers.
            </p>
            <p class="banner__meta">{{ summary() }}</p>
          </div>
          <button type="button" class="btn btn--sm" (click)="warmUp.retry()">Try again</button>
        </div>
      </div>
    } @else if (warmUp.waking()) {
      <div class="banner-stack">
        <div class="banner banner--info" role="status">
          <div class="banner__body">
            <p class="banner__title">
              <span class="spinner" aria-hidden="true"></span>
              The demo backend runs on a free tier and sleeps when idle. Waking it up, this takes up to a minute.
            </p>
            <p class="banner__meta">
              <span class="num">{{ warmUp.elapsedSeconds() }} s</span> · {{ summary() }}. Requests you make now are
              sent as soon as it answers.
            </p>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .spinner {
      display: inline-block;
      width: 10px;
      height: 10px;
      margin-right: 6px;
      border: 2px solid var(--accent);
      border-right-color: transparent;
      border-radius: 50%;
      animation: spin 0.9s linear infinite;
      vertical-align: -1px;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
  `,
})
export class WarmUpBannerComponent {
  protected readonly warmUp = inject(WarmUpService);

  protected summary(): string {
    return this.warmUp
      .services()
      .map((s) => `${s.label} ${STATUS_WORDS[s.status]}`)
      .join(', ');
  }
}

const STATUS_WORDS: Record<string, string> = {
  idle: 'not checked',
  checking: 'checking',
  waking: 'waking',
  ready: 'ready',
  failed: 'not answering',
};
