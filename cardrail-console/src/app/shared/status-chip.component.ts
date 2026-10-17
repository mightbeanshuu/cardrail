import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type AuthorizationStatus = 'PENDING' | 'AUTHORIZED' | 'DECLINED' | 'REVERSED' | 'CAPTURED' | 'EXPIRED';

type Tone = 'info' | 'success' | 'danger' | 'neutral' | 'warning';

const STATUS_DISPLAY: Record<string, { label: string; tone: Tone }> = {
  PENDING: { label: 'Pending', tone: 'neutral' },
  AUTHORIZED: { label: 'Authorized', tone: 'info' },
  CAPTURED: { label: 'Captured', tone: 'success' },
  DECLINED: { label: 'Declined', tone: 'danger' },
  REVERSED: { label: 'Reversed', tone: 'neutral' },
  EXPIRED: { label: 'Expired', tone: 'warning' },
};

/** Status label with a tone. The text is always present, so colour is never the only signal. */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip" [class]="'chip--' + display().tone">
    <span class="chip__dot" aria-hidden="true"></span>{{ display().label }}</span>`,
})
export class StatusChipComponent {
  readonly status = input.required<string | null>();

  protected readonly display = computed(
    () => STATUS_DISPLAY[this.status() ?? ''] ?? { label: this.status() ?? 'Unknown', tone: 'neutral' as Tone },
  );
}

/** Generic chip for non-authorization states (settlement runs, reconciliation, audit). */
@Component({
  selector: 'app-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip" [class]="'chip--' + tone()"><span class="chip__dot" aria-hidden="true"></span><ng-content /></span>`,
})
export class ChipComponent {
  readonly tone = input<Tone>('neutral');
}
