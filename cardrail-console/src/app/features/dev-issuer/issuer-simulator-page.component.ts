import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { FaultSettings, IssuerFaultsService } from './issuer-faults.service';

interface Preset {
  readonly label: string;
  readonly latencyMs: number;
  readonly failurePercent: number;
  readonly note: string;
}

const PRESETS: readonly Preset[] = [
  { label: 'Healthy', latencyMs: 0, failurePercent: 0, note: 'Issuer answers immediately.' },
  {
    label: 'Slow issuer',
    latencyMs: 2000,
    failurePercent: 0,
    note: 'Above the switch issuer timeout (1500 ms by default), so requests fall back to STIP.',
  },
  { label: 'Issuer failing', latencyMs: 0, failurePercent: 100, note: 'Every call fails; the circuit breaker opens.' },
];

/** Dev only: issuer latency and failure injection, to demonstrate stand-in processing. */
@Component({
  selector: 'app-issuer-simulator-page',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page__header">
        <h1 tabindex="-1">Issuer simulator</h1>
        <p class="page__subtitle">Dev profile only</p>
      </div>

      <section class="panel" aria-labelledby="current-h">
        <div class="panel__header">
          <h2 id="current-h">Current settings</h2>
          <button type="button" class="btn btn--sm" (click)="load()" [disabled]="busy()">Refresh</button>
        </div>
        <div class="panel__body" aria-live="polite">
          @if (current(); as c) {
            <dl class="dl">
              <dt>Latency</dt>
              <dd class="mono">{{ c.latencyMs }} ms</dd>
              <dt>Failure rate</dt>
              <dd class="mono">{{ percent(c.failureRate) }} %</dd>
            </dl>
          } @else if (unavailable()) {
            <p>
              The switch did not answer <code>/dev/issuer/faults</code>. It is only available when the switch runs with
              the <code>dev</code> Spring profile.
            </p>
          } @else {
            <p class="muted" role="status">Loading…</p>
          }
        </div>
      </section>

      <section class="panel" aria-labelledby="set-h">
        <div class="panel__header"><h2 id="set-h">Change</h2></div>
        <div class="panel__body stack">
          <div class="row" role="group" aria-label="Presets">
            @for (p of presets; track p.label) {
              <button type="button" class="btn btn--sm" (click)="usePreset(p)" [attr.aria-describedby]="'preset-' + $index">
                {{ p.label }}
              </button>
              <span class="sr-only" [id]="'preset-' + $index">{{ p.note }}</span>
            }
          </div>
          <form class="filters flush" [formGroup]="form" (ngSubmit)="apply()" novalidate>
            <div class="field">
              <label for="latency">Latency (ms)</label>
              <input id="latency" class="input input--amount" type="number" min="0" max="60000" step="100"
                     formControlName="latencyMs" [attr.aria-invalid]="form.controls.latencyMs.invalid"
                     aria-describedby="latency-hint" />
              <p id="latency-hint" class="field__hint">0 to 60000</p>
            </div>
            <div class="field">
              <label for="failure">Failure rate (%)</label>
              <input id="failure" class="input input--amount" type="number" min="0" max="100" step="1"
                     formControlName="failurePercent" [attr.aria-invalid]="form.controls.failurePercent.invalid"
                     aria-describedby="failure-hint" />
              <p id="failure-hint" class="field__hint">0 to 100</p>
            </div>
            <div class="filters__actions">
              <button type="button" class="btn" (click)="reset()" [disabled]="busy()">Restore defaults</button>
              <button type="submit" class="btn btn--primary" [disabled]="busy() || form.invalid">Apply</button>
            </div>
          </form>
          @if (saved()) {
            <p class="muted" role="status">Applied. New authorizations use these settings.</p>
          }
        </div>
      </section>
    </div>
  `,
  styles: `.flush { padding: 0; }`,
})
export class IssuerSimulatorPageComponent {
  private readonly faults = inject(IssuerFaultsService);

  protected readonly presets = PRESETS;
  protected readonly current = signal<FaultSettings | null>(null);
  protected readonly unavailable = signal(false);
  protected readonly busy = signal(false);
  protected readonly saved = signal(false);

  protected readonly form = new FormGroup({
    latencyMs: new FormControl(0, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(0), Validators.max(60000), Validators.pattern(/^\d+$/)],
    }),
    failurePercent: new FormControl(0, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(0), Validators.max(100)],
    }),
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.busy.set(true);
    this.faults.current().subscribe({
      next: (s) => this.show(s),
      error: () => {
        this.busy.set(false);
        this.unavailable.set(true);
      },
    });
  }

  protected usePreset(p: Preset): void {
    this.form.setValue({ latencyMs: p.latencyMs, failurePercent: p.failurePercent });
    this.saved.set(false);
  }

  protected apply(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.saved.set(false);
    // Percent with at most two decimals → rate; rounding keeps 0.1 + 0.2 style noise out of the body.
    const failureRate = Math.round(Number(v.failurePercent) * 100) / 10000;
    this.faults.set({ latencyMs: Number(v.latencyMs), failureRate }).subscribe({
      next: (s) => {
        this.show(s);
        this.saved.set(true);
      },
      error: () => this.busy.set(false),
    });
  }

  protected reset(): void {
    this.busy.set(true);
    this.saved.set(false);
    this.faults.reset().subscribe({
      next: (s) => {
        this.show(s);
        this.saved.set(true);
      },
      error: () => this.busy.set(false),
    });
  }

  protected percent(rate: number): string {
    return (Math.round(rate * 10000) / 100).toString();
  }

  private show(s: FaultSettings): void {
    this.busy.set(false);
    this.unavailable.set(false);
    this.current.set(s);
    this.form.setValue({ latencyMs: s.latencyMs, failurePercent: Number(this.percent(s.failureRate)) });
  }
}
