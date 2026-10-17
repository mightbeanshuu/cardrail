import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output } from '@angular/core';
import { AbstractControl, FormControl, ReactiveFormsModule, ValidationErrors, ValidatorFn } from '@angular/forms';
import { APP_CONFIG } from '../../core/config/app-config';
import {
  currencyExponent,
  currencyInfo,
  describeAmountParseError,
  formatMinor,
  minorToDecimalString,
  parseMajorToMinor,
} from '../../shared/money';

export interface CaptureAmountError {
  readonly message: string;
}

/**
 * Validates an optional partial-capture amount typed in major units. Empty means "capture the full
 * amount". Otherwise it must parse exactly (no float maths), be greater than zero and not exceed
 * the authorized amount.
 */
export function captureAmountValidator(authorizedMinor: () => number, currency: () => number): ValidatorFn {
  return (control: AbstractControl<string | null>): ValidationErrors | null => {
    const raw = (control.value ?? '').trim();
    if (raw === '') {
      return null;
    }
    const exponent = currencyExponent(currency());
    const parsed = parseMajorToMinor(raw, exponent);
    if (!parsed.ok) {
      return { amount: { message: describeAmountParseError(parsed.error, exponent) } satisfies CaptureAmountError };
    }
    if (parsed.minor <= 0) {
      return { amount: { message: 'Amount must be greater than zero.' } };
    }
    if (parsed.minor > authorizedMinor()) {
      return {
        amount: {
          message: `Cannot exceed the authorized ${formatMinor(authorizedMinor(), currency())}.`,
        },
      };
    }
    return null;
  };
}

/** Full or partial capture of an authorization. Emits the amount in minor units, or null for full. */
@Component({
  selector: 'app-capture-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="stack" (submit)="$event.preventDefault(); submit()" novalidate aria-labelledby="capture-heading">
      <h3 id="capture-heading">Capture</h3>
      <div class="field">
        <label for="capture-amount">Amount to capture ({{ currencyCode() }})</label>
        <div class="row">
          <input id="capture-amount" class="input input--amount" [formControl]="amount" inputmode="decimal"
                 autocomplete="off" [placeholder]="placeholder()" [attr.aria-invalid]="showError()"
                 [attr.aria-describedby]="showError() ? 'capture-amount-hint capture-amount-err' : 'capture-amount-hint'" />
          <button type="submit" class="btn btn--primary" [disabled]="!!disabledReason() || busy()">
            {{ busy() ? 'Capturing…' : 'Capture' }}
          </button>
        </div>
        <p id="capture-amount-hint" class="field__hint">
          Leave empty to capture the full {{ authorizedLabel() }}. A partial capture releases the rest of the hold.
        </p>
        @if (showError()) {
          <p id="capture-amount-err" class="field__error">{{ errorMessage() }}</p>
        }
      </div>
    </form>
  `,
})
export class CaptureFormComponent {
  private readonly locale = inject(APP_CONFIG).locale;

  readonly authorizedMinor = input.required<number>();
  readonly currency = input.required<number>();
  /** When set, the form is disabled and this text explains why (shown by the parent). */
  readonly disabledReason = input<string | null>(null);
  readonly busy = input(false);
  readonly capture = output<number | null>();

  readonly amount = new FormControl<string>('', {
    nonNullable: true,
    validators: [captureAmountValidator(() => this.authorizedMinor(), () => this.currency())],
  });

  private submitted = false;

  protected readonly currencyCode = computed(() => currencyInfo(this.currency())?.alpha ?? String(this.currency()));
  protected readonly authorizedLabel = computed(() =>
    formatMinor(this.authorizedMinor(), this.currency(), this.locale),
  );
  protected readonly placeholder = computed(() =>
    minorToDecimalString(this.authorizedMinor(), currencyExponent(this.currency())),
  );

  constructor() {
    // Re-validate when the authorization changes underneath the form (e.g. after a reload).
    effect(() => {
      this.authorizedMinor();
      this.currency();
      this.amount.updateValueAndValidity({ emitEvent: false });
    });
    effect(() => {
      if (this.disabledReason()) {
        this.amount.disable({ emitEvent: false });
      } else {
        this.amount.enable({ emitEvent: false });
      }
    });
  }

  protected showError(): boolean {
    return this.amount.invalid && (this.amount.dirty || this.amount.touched || this.submitted);
  }

  protected errorMessage(): string {
    return (this.amount.getError('amount') as CaptureAmountError | null)?.message ?? '';
  }

  submit(): void {
    this.submitted = true;
    this.amount.markAsTouched();
    if (this.disabledReason() || this.busy() || this.amount.invalid) {
      return;
    }
    const raw = this.amount.value.trim();
    if (raw === '') {
      this.capture.emit(null);
      return;
    }
    const parsed = parseMajorToMinor(raw, currencyExponent(this.currency()));
    if (parsed.ok) {
      this.capture.emit(parsed.minor);
    }
  }

  reset(): void {
    this.submitted = false;
    this.amount.reset('');
  }
}
