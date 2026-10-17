import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ErrorBannerService } from './error-banner.service';

@Component({
  selector: 'app-error-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (banner.entries().length > 0) {
      <section class="banner-stack" aria-label="Errors">
        @for (entry of banner.entries(); track entry.id) {
          <div class="banner banner--error" role="alert">
            <div class="banner__body">
              <p class="banner__title">
                <span class="banner__glyph" aria-hidden="true">!</span>
                {{ entry.error.title }}
                @if (entry.count > 1) {
                  <span class="muted">(×{{ entry.count }})</span>
                }
              </p>
              @if (entry.error.detail) {
                <p class="banner__detail">{{ entry.error.detail }}</p>
              }
              @if (entry.error.fieldErrors.length > 0) {
                <ul class="banner__fields">
                  @for (f of entry.error.fieldErrors; track f) {
                    <li>{{ f }}</li>
                  }
                </ul>
              }
              <p class="banner__meta">
                {{ entry.error.status === 0 ? 'No HTTP response' : 'HTTP ' + entry.error.status }}
                @if (entry.error.correlationId) {
                  · correlation id <code>{{ entry.error.correlationId }}</code>
                }
              </p>
            </div>
            <button type="button" class="btn btn--ghost btn--icon" (click)="banner.dismiss(entry.id)"
                    [attr.aria-label]="'Dismiss: ' + entry.error.title">
              <span aria-hidden="true">×</span>
            </button>
          </div>
        }
      </section>
    }
  `,
})
export class ErrorBannerComponent {
  protected readonly banner = inject(ErrorBannerService);
}
