import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { DEMO_CARDS, DEMO_CURRENCY, DemoCard, demoCard, maskedPan } from '../../demo/demo-cards';
import { currencyExponent, currencyInfo, describeAmountParseError, parseMajorToMinor } from '../../shared/money';
import { DEMO_MERCHANTS, demoMerchant } from './merchants';
import { TerminalInput } from './terminal-request';

/** Largest amount the form accepts; the switch's own limits (61) apply well below it. */
const MAX_AMOUNT_MINOR = 9_999_999_999; // 99,999,999.99

/**
 * The terminal's keypad: a card from the fixed list of test cards (there is deliberately no field
 * for typing a card number), an amount in major units and a merchant category.
 */
@Component({
  selector: 'app-terminal-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="stack" [formGroup]="form" (ngSubmit)="submit()" novalidate aria-label="Authorization request">
      <fieldset class="cards">
        <legend class="field__label">Test card</legend>
        @for (c of cards; track c.id) {
          <label class="card-option" [class.is-selected]="cardId() === c.id">
            <input type="radio" formControlName="cardId" [value]="c.id" [attr.aria-describedby]="'card-hint-' + c.id" />
            <span class="card-option__main">
              <span class="mono card-option__pan">{{ masked(c) }}</span>
              <span class="card-option__label">{{ c.label }}</span>
            </span>
            <span class="card-option__hint muted" [id]="'card-hint-' + c.id">{{ c.hint }}</span>
          </label>
        }
      </fieldset>

      <div class="form-row">
        <div class="field">
          <label for="t-amount">Amount ({{ currencyCode }})</label>
          <input id="t-amount" class="input input--amount" formControlName="amount" inputmode="decimal" autocomplete="off"
                 spellcheck="false" maxlength="14" [attr.aria-invalid]="!!amountError()"
                 [attr.aria-describedby]="amountError() ? 't-amount-err' : 't-amount-hint'" />
          @if (amountError(); as e) {
            <p id="t-amount-err" class="field__error">{{ e }}</p>
          } @else {
            <p id="t-amount-hint" class="field__hint">Major units, for example 250.00</p>
          }
        </div>
        <div class="field grow">
          <label for="t-merchant">Merchant category</label>
          <select id="t-merchant" class="select" formControlName="mcc">
            @for (m of merchants; track m.mcc) {
              <option [value]="m.mcc">{{ m.category }} · {{ m.mcc }}{{ m.note ? ' (' + m.note + ')' : '' }}</option>
            }
          </select>
          <p class="field__hint">{{ merchantName() }}</p>
        </div>
      </div>

      <div class="row">
        <button type="submit" class="btn btn--primary btn--wide" [disabled]="busy()">
          {{ busy() ? 'Sending…' : 'Send' }}
        </button>
        <span class="muted small">POST /v1/authorizations, a JSON mirror of an ISO 8583 0100</span>
      </div>
    </form>
  `,
  styles: `
    .cards { display: grid; gap: 6px; margin: 0; padding: 0; border: 0; min-width: 0; }
    .cards legend { margin-bottom: 6px; padding: 0; }
    .card-option {
      display: grid; grid-template-columns: auto 1fr; gap: 0 10px; align-items: center;
      padding: 7px 10px; border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer;
    }
    .card-option:hover { background: var(--surface-sunken); }
    .card-option.is-selected { border-color: var(--accent); background: var(--accent-soft); }
    .card-option input { margin: 0; accent-color: var(--accent); }
    .card-option__main { display: flex; flex-wrap: wrap; gap: 2px 12px; align-items: baseline; }
    .card-option__pan { font-size: 13px; }
    .card-option__label { font-weight: 600; }
    .card-option__hint { grid-column: 2; font-size: 12px; }
    .form-row { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-start; }
    .grow { flex: 1 1 220px; }
    .grow .select { width: 100%; }
    .btn--wide { min-width: 120px; }
    .small { font-size: 12px; }
  `,
})
export class TerminalFormComponent {
  readonly busy = input(false);
  readonly send = output<TerminalInput>();

  protected readonly cards = DEMO_CARDS;
  protected readonly merchants = DEMO_MERCHANTS;
  protected readonly currencyCode = currencyInfo(DEMO_CURRENCY)?.alpha ?? String(DEMO_CURRENCY);
  private readonly exponent = currencyExponent(DEMO_CURRENCY);

  protected readonly form = new FormGroup({
    cardId: new FormControl(DEMO_CARDS[0].id, { nonNullable: true }),
    amount: new FormControl('250.00', { nonNullable: true }),
    mcc: new FormControl(DEMO_MERCHANTS[0].mcc, { nonNullable: true }),
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  private readonly submitted = signal(false);

  protected readonly cardId = computed(() => this.value().cardId);
  protected readonly merchantName = computed(() => {
    const m = demoMerchant(this.value().mcc ?? '');
    return m ? `${m.merchantName}, merchant id ${m.merchantId}` : '';
  });
  protected readonly amountError = computed(() => {
    const raw = this.value().amount ?? '';
    if (!this.submitted() && raw.trim() === '') return null;
    return this.validateAmount(raw).error;
  });

  /** Pre-fills the form, used by the stand-in suggestions. */
  fill(cardId: string, amount: string): void {
    if (!demoCard(cardId)) return;
    this.form.patchValue({ cardId, amount });
  }

  protected masked(card: DemoCard): string {
    return maskedPan(card);
  }

  protected submit(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const { minor, error } = this.validateAmount(v.amount);
    const card = demoCard(v.cardId);
    const merchant = demoMerchant(v.mcc);
    if (error || minor == null || !card || !merchant || this.busy()) return;
    this.send.emit({ card, merchant, amountMinor: minor });
  }

  private validateAmount(raw: string): { minor: number | null; error: string | null } {
    const parsed = parseMajorToMinor(raw, this.exponent);
    if (!parsed.ok) return { minor: null, error: describeAmountParseError(parsed.error, this.exponent) };
    if (parsed.minor <= 0) return { minor: null, error: 'Amount must be greater than zero.' };
    if (parsed.minor > MAX_AMOUNT_MINOR) return { minor: null, error: 'Amount is too large for this demo.' };
    return { minor: parsed.minor, error: null };
  }
}
