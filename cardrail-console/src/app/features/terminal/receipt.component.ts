import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { APP_CONFIG } from '../../core/config/app-config';
import { MaskedPanPipe, MoneyPipe } from '../../shared/pipes';
import { responseCodeKind, responseCodeLabel } from '../../shared/response-codes';
import { StipBadgeComponent } from '../../shared/response-code.component';
import { explainExchange } from './explain';
import { TerminalExchange } from './terminal.service';

/** Codes the switch answers itself, without asking the issuer. */
const SWITCH_DECIDED = new Set(['12', '13', '30', '54', '57', '61', '65', '94', '96']);

/** The terminal's printout for one exchange, plus the retransmission controls. */
@Component({
  selector: 'app-receipt',
  imports: [RouterLink, MoneyPipe, MaskedPanPipe, StipBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (exchange(); as x) {
      <div class="receipt" [class]="'receipt receipt--' + tone()" aria-live="polite">
        <div class="receipt__head">
          @if (x.response; as r) {
            <span class="receipt__code mono" [attr.aria-label]="'Response code ' + r.responseCode">{{ r.responseCode }}</span>
            <div class="receipt__title">
              <p class="receipt__label">{{ label() }}</p>
              <p class="muted small">{{ kindLabel() }}</p>
            </div>
            @if (r.standIn) {
              <app-stip-badge [standIn]="true" />
            }
          } @else {
            <span class="receipt__code mono" aria-hidden="true">--</span>
            <div class="receipt__title">
              <p class="receipt__label">{{ x.error?.isRateLimited ? 'Too many requests' : 'No response code' }}</p>
              <p class="muted small">{{ x.error?.status ? 'HTTP ' + x.error.status : 'No HTTP response' }}</p>
            </div>
          }
        </div>
        <p class="receipt__explain">{{ explanation() }}</p>

        <dl class="receipt__lines">
          <dt>Amount</dt>
          <dd class="num">{{ x.amountMinor | money: x.currency }}</dd>
          <dt>Merchant</dt>
          <dd>{{ x.merchant.merchantName }}<span class="muted">{{ ' · MCC ' + x.merchant.mcc }}</span></dd>
          <dt>Card</dt>
          <dd><span class="mono">{{ (x.response?.maskedPan ?? x.card.maskedPan) | maskedPan }}</span><span class="muted">{{ ' · ' + x.card.label }}</span></dd>
          <dt>Token</dt>
          <dd class="mono">{{ x.response?.token ?? '—' }}</dd>
          <dt>Auth code</dt>
          <dd class="mono">{{ x.response?.authCode ?? '—' }}</dd>
          <dt>STAN · RRN</dt>
          <dd class="mono">{{ x.stan }} · {{ x.rrn }}</dd>
          <dt><abbr title="ISO 8583 field 7, MMDDhhmmss UTC">F7</abbr> · terminal</dt>
          <dd class="mono">{{ x.transmissionDateTime }} · {{ x.terminalId }}</dd>
          <dt>Latency</dt>
          <dd class="num">{{ x.latencyMs }} ms <span class="muted small">in the browser</span></dd>
        </dl>

        @if (x.response?.id; as id) {
          <p><a class="btn btn--sm" [routerLink]="['/authorizations', id]">Open authorization</a>
            <span class="muted small"> to capture or reverse it</span></p>
        }

        <div class="dup">
          <p class="field__label">Duplicate detection</p>
          <div class="receipt__actions">
            <button type="button" class="btn btn--sm" [disabled]="busy()" (click)="retransmit.emit(x.seq)"
                    aria-describedby="retransmit-hint">
              Send again (retransmission)
            </button>
            <button type="button" class="btn btn--sm" [disabled]="busy()" (click)="conflicting.emit(x.seq)"
                    aria-describedby="retransmit-hint">
              Same key, amount +1.00
            </button>
          </div>
          <p id="retransmit-hint" class="muted small">
            A terminal that gets no answer sends the same message again. The switch keys duplicates on acquirer, STAN,
            transmission time and terminal: an identical copy gets the original answer, a changed one gets 94.
          </p>
        </div>
      </div>
    } @else {
      <div class="receipt receipt--empty">
        <p class="receipt__label">No request sent yet</p>
        <p class="muted">Choose a test card, an amount and a merchant, then Send. The switch's answer is printed here.</p>
      </div>
    }
  `,
  styles: `
    .receipt { display: grid; gap: 12px; padding: 14px; border-top: 3px solid var(--border-strong); }
    .receipt--approved { border-top-color: var(--success); }
    .receipt--declined { border-top-color: var(--danger); }
    .receipt--system { border-top-color: var(--warning); }
    .receipt--empty { gap: 4px; }
    .receipt__head { display: flex; align-items: center; gap: 12px; }
    .receipt__code { font-size: 28px; font-weight: 700; line-height: 1; min-width: 2ch; }
    .receipt--approved .receipt__code { color: var(--success); }
    .receipt--declined .receipt__code { color: var(--danger); }
    .receipt--system .receipt__code { color: var(--warning); }
    .receipt__title { flex: 1; min-width: 0; }
    .receipt__label { font-size: 15px; font-weight: 600; }
    .receipt__explain { max-width: 60ch; }
    .receipt__lines {
      display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px; margin: 0;
      padding: 10px 0; border-top: 1px dashed var(--border-strong); border-bottom: 1px dashed var(--border-strong);
      font-variant-numeric: tabular-nums;
    }
    .receipt__lines dt { color: var(--text-muted); }
    .receipt__lines dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
    .receipt__lines dd.num { text-align: left; }
    .receipt__actions { display: flex; flex-wrap: wrap; gap: 8px; }
    .dup { display: grid; gap: 6px; }
    .small { font-size: 12px; }
  `,
})
export class ReceiptComponent {
  private readonly locale = inject(APP_CONFIG).locale;

  readonly exchange = input<TerminalExchange | null>(null);
  /** The exchange a retransmission repeats, so the receipt can say whether the answer matched. */
  readonly original = input<TerminalExchange | null>(null);
  readonly busy = input(false);
  readonly retransmit = output<number>();
  readonly conflicting = output<number>();

  protected readonly tone = computed(() => {
    const x = this.exchange();
    if (!x?.response) return 'system';
    return responseCodeKind(x.response.responseCode);
  });
  protected readonly label = computed(() => responseCodeLabel(this.exchange()?.response?.responseCode));
  protected readonly kindLabel = computed(() => {
    const x = this.exchange();
    if (!x) return '';
    const code = x.response?.responseCode ?? '';
    const by = x.response?.standIn
      ? 'decided by stand-in processing'
      : SWITCH_DECIDED.has(code)
        ? 'decided by the switch'
        : 'decided by the issuer';
    switch (x.kind) {
      case 'retransmission':
        return `Retransmission of request #${x.originalSeq}`;
      case 'conflicting':
        return `Request #${x.originalSeq} with a changed amount`;
      default:
        return `Request #${x.seq}, ${by}`;
    }
  });
  protected readonly explanation = computed(() => {
    const x = this.exchange();
    return x ? explainExchange(x, this.original(), this.locale) : '';
  });
}
