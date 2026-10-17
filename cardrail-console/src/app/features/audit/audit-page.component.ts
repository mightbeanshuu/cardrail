import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ChipComponent } from '../../shared/status-chip.component';
import { LoadState, asApiError } from '../../shared/load-state';
import { formatUtc } from '../../shared/datetime';
import { AuditService, ChainVerification } from './audit.service';

interface Checked {
  readonly result: ChainVerification;
  readonly checkedAt: string;
}

@Component({
  selector: 'app-audit-page',
  imports: [ChipComponent, DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page__header">
        <div class="page__heading">
          <h1 tabindex="-1">Audit chain</h1>
          <p class="page__lede">
            Every state change in the switch is appended to a log where each record carries the hash of the one before
            it. Editing or deleting any record breaks the chain from that point, which is what an auditor checks for.
          </p>
        </div>
      </div>
      <section class="panel" aria-labelledby="verify-h">
        <div class="panel__header">
          <h2 id="verify-h">Verify</h2>
          <button type="button" class="btn btn--primary" (click)="verify()" [disabled]="state().status === 'loading'">
            {{ state().status === 'loading' ? 'Verifying…' : 'Run verification' }}
          </button>
        </div>
        <div class="panel__body stack">
          <p class="muted">
            Verification recomputes each record's hash from its predecessor on the switch and reports the first record
            that does not match.
          </p>
          <div aria-live="polite">
            @switch (state().status) {
              @case ('loading') {
                <p role="status">Recomputing the chain…</p>
              }
              @case ('error') {
                <p>Verification could not be run. See the error above.</p>
              }
            }
            @if (state().status === 'idle') {
              <p>Not run yet. Send a payment from the <a routerLink="/terminal">terminal</a> first if you want to see
                the record count grow, then run verification.</p>
            }
            @if (checked(); as c) {
              @if (c.result.intact) {
                <div class="result">
                  <app-chip tone="success">Intact</app-chip>
                  <p><strong class="num">{{ c.result.recordsVerified | number }}</strong> records verified, no breaks.</p>
                </div>
              } @else {
                <div class="result">
                  <app-chip tone="danger">Broken</app-chip>
                  <dl class="dl">
                    <dt>First broken seq</dt>
                    <dd class="mono">{{ c.result.firstBrokenSeq ?? 'unknown' }}</dd>
                    <dt>Verified before break</dt>
                    <dd class="num left">{{ c.result.recordsVerified | number }}</dd>
                    <dt>Reason</dt>
                    <dd>{{ c.result.reason ?? '—' }}</dd>
                  </dl>
                </div>
              }
              <p class="muted mono">Checked {{ c.checkedAt }} UTC</p>
            }
          </div>
        </div>
      </section>
    </div>
  `,
  styles: `
    .result { display: grid; gap: 8px; justify-items: start; }
    .left { text-align: left; }
  `,
})
export class AuditPageComponent {
  private readonly audit = inject(AuditService);

  protected readonly state = signal<LoadState<ChainVerification>>({ status: 'idle' });
  protected readonly checked = signal<Checked | null>(null);

  protected verify(): void {
    this.state.set({ status: 'loading' });
    this.checked.set(null);
    this.audit.verify().subscribe({
      next: (result) => {
        this.state.set({ status: 'ok', data: result });
        this.checked.set({ result, checkedAt: formatUtc(new Date().toISOString()) });
      },
      error: (e: unknown) => this.state.set({ status: 'error', error: asApiError(e) }),
    });
  }
}
