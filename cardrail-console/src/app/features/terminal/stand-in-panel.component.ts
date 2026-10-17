import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { DEMO_CARDS, DEMO_CURRENCY, panSuffix } from '../../demo/demo-cards';
import { formatMinor, minorToDecimalString } from '../../shared/money';
import { FaultSettings, IssuerFaultsService } from '../dev-issuer/issuer-faults.service';
import { ISSUER_TIMEOUT_MS } from './explain';

export interface StandInSuggestion {
  readonly cardId: string;
  readonly amount: string;
}

/** Latency comfortably above the switch's issuer time limit. */
export const SLOW_ISSUER: FaultSettings = { latencyMs: 3000, failureRate: 0 };
export const FAILING_ISSUER: FaultSettings = { latencyMs: 0, failureRate: 1 };

/** One amount under and one over the first card's stand-in limit, from the card config. */
function standInTries() {
  const card = DEMO_CARDS[0];
  const limit = card.standInLimitMinor;
  const limitText = formatMinor(limit, DEMO_CURRENCY);
  return [
    { minor: Math.round((limit * 4) / 5), outcome: `under its ${limitText} stand-in limit: approved by STIP` },
    { minor: Math.round((limit * 6) / 5), outcome: `over the limit: declined with 91` },
  ].map((t) => ({
    cardId: card.id,
    cardLabel: card.label,
    panSuffix: panSuffix(card),
    amount: minorToDecimalString(t.minor, 2),
    display: formatMinor(t.minor, DEMO_CURRENCY),
    outcome: t.outcome,
  }));
}

type IssuerMode = 'healthy' | 'slow' | 'failing' | 'custom';

export function issuerMode(s: FaultSettings): IssuerMode {
  if (s.failureRate >= 1) return 'failing';
  if (s.latencyMs > ISSUER_TIMEOUT_MS) return 'slow';
  if (s.latencyMs === 0 && s.failureRate === 0) return 'healthy';
  return 'custom';
}

/** Stand-in processing demo: push the simulated issuer past the switch's time limit, then pay. */
@Component({
  selector: 'app-stand-in-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stack">
      <p>
        When the issuing bank does not answer within {{ timeoutText }} ms, or the circuit breaker around it is open, the
        switch decides on the bank's behalf: it approves amounts under a per-card-range limit and declines the rest
        with 91.
      </p>

      <div class="issuer-state" aria-live="polite">
        <span class="muted">Issuer now:</span>
        @switch (state()) {
          @case ('loading') { <span>checking…</span> }
          @case ('unavailable') {
            <span>The issuer simulator is not enabled on this deployment (the switch exposes it under its
              <code>dev</code> and <code>demo</code> profiles).</span>
          }
          @default {
            <strong>{{ modeLabel() }}</strong>
          }
        }
      </div>

      <div class="row" role="group" aria-label="Issuer behaviour">
        <button type="button" class="btn btn--primary" [disabled]="busy() || state() === 'unavailable'"
                (click)="apply(slow)">Make the issuer slow</button>
        <button type="button" class="btn" [disabled]="busy() || state() === 'unavailable'" (click)="apply(failing)">
          Make the issuer fail
        </button>
        <button type="button" class="btn btn--ghost" [disabled]="busy() || state() === 'unavailable' || mode() === 'healthy'"
                (click)="restore()">Restore</button>
      </div>

      <div class="try">
        <p class="field__label">Then send</p>
        <ul class="try__list">
          @for (t of tries; track t.amount) {
            <li>
              <button type="button" class="btn-link" (click)="suggest.emit({ cardId: t.cardId, amount: t.amount })">
                {{ t.cardLabel }} {{ t.panSuffix }}, {{ t.display }}
              </button>
              <span class="muted">{{ t.outcome }}</span>
            </li>
          }
        </ul>
        <p class="muted small">Clicking fills the form; press Send.</p>
      </div>
      <p class="muted small">
        The issuer setting is shared by everyone using this demo right now. Restore it when you are done.
      </p>
    </div>
  `,
  styles: `
    .issuer-state { display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
    .try { display: grid; gap: 4px; }
    .try__list { display: grid; gap: 4px; margin: 0; padding-left: 18px; }
    .try__list .muted { margin-left: 4px; }
    .small { font-size: 12px; }
  `,
})
export class StandInPanelComponent {
  private readonly faults = inject(IssuerFaultsService);

  readonly suggest = output<StandInSuggestion>();

  protected readonly timeoutText = ISSUER_TIMEOUT_MS.toLocaleString('en-IN');
  protected readonly tries = standInTries();
  protected readonly slow = SLOW_ISSUER;
  protected readonly failing = FAILING_ISSUER;
  protected readonly settings = signal<FaultSettings | null>(null);
  protected readonly state = signal<'loading' | 'ok' | 'unavailable'>('loading');
  protected readonly busy = signal(false);

  protected readonly mode = computed(() => {
    const s = this.settings();
    return s ? issuerMode(s) : 'healthy';
  });
  protected readonly modeLabel = computed(() => {
    const s = this.settings();
    if (!s) return '';
    switch (issuerMode(s)) {
      case 'healthy':
        return 'answering normally';
      case 'slow':
        return `slow, ${s.latencyMs.toLocaleString('en-IN')} ms per call (the switch waits ${ISSUER_TIMEOUT_MS.toLocaleString('en-IN')} ms)`;
      case 'failing':
        return 'failing every call';
      default:
        return `${s.latencyMs} ms latency, ${Math.round(s.failureRate * 100)} % of calls failing`;
    }
  });

  constructor() {
    this.faults.current({ quiet: true }).subscribe({
      next: (s) => this.show(s),
      error: () => this.state.set('unavailable'),
    });
  }

  protected apply(settings: FaultSettings): void {
    this.busy.set(true);
    this.faults.set(settings).subscribe({ next: (s) => this.show(s), error: () => this.busy.set(false) });
  }

  protected restore(): void {
    this.busy.set(true);
    this.faults.reset().subscribe({ next: (s) => this.show(s), error: () => this.busy.set(false) });
  }

  private show(s: FaultSettings): void {
    this.busy.set(false);
    this.settings.set(s);
    this.state.set('ok');
  }
}
