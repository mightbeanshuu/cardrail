import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { responseCodeKind, responseCodeLabel } from './response-codes';

/** `00 Approved`: the code in monospace plus its label, tinted by kind. */
@Component({
  selector: 'app-response-code',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (code()) {
      <span class="rc" [class]="'rc--' + kind()"><code class="rc__code">{{ code() }}</code> {{ label() }}</span>
    } @else {
      <span class="muted">—</span>
    }`,
})
export class ResponseCodeComponent {
  readonly code = input<string | null>(null);
  protected readonly label = computed(() => responseCodeLabel(this.code()));
  protected readonly kind = computed(() => responseCodeKind(this.code()));
}

/** Badge for stand-in (STIP) decisions, with a full-text alternative for screen readers. */
@Component({
  selector: 'app-stip-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (standIn()) {
      <abbr class="badge badge--stip" title="Stand-in processing: decided by the switch on the issuer's behalf">STIP</abbr>
    } @else {
      <span class="muted" aria-label="Not stand-in">—</span>
    }`,
})
export class StipBadgeComponent {
  readonly standIn = input<boolean>(false);
}
