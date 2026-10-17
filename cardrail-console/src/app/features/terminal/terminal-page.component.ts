import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { MoneyPipe, UtcPipe } from '../../shared/pipes';
import { ResponseCodeComponent, StipBadgeComponent } from '../../shared/response-code.component';
import { ReceiptComponent } from './receipt.component';
import { StandInPanelComponent, StandInSuggestion } from './stand-in-panel.component';
import { TerminalFormComponent } from './terminal-form.component';
import { DEMO_ACQUIRER_ID, TerminalInput } from './terminal-request';
import { ExchangeKind, TerminalExchange, TerminalService } from './terminal.service';

const KIND_LABELS: Record<ExchangeKind, string> = {
  new: 'New',
  retransmission: 'Retransmission',
  conflicting: 'Changed amount',
};

@Component({
  selector: 'app-terminal-page',
  imports: [
    RouterLink,
    MoneyPipe,
    UtcPipe,
    ResponseCodeComponent,
    StipBadgeComponent,
    TerminalFormComponent,
    ReceiptComponent,
    StandInPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './terminal-page.component.html',
  styleUrl: './terminal-page.component.css',
})
export class TerminalPageComponent {
  private readonly terminal = inject(TerminalService);
  private readonly form = viewChild(TerminalFormComponent);

  protected readonly devTools = inject(APP_CONFIG).devTools;
  protected readonly terminalId = this.terminal.terminalId;
  protected readonly acquirerId = DEMO_ACQUIRER_ID;
  protected readonly history = this.terminal.history;
  protected readonly busy = signal(false);
  protected readonly latest = computed(() => this.history()[0] ?? null);
  protected readonly original = computed(() => {
    const x = this.latest();
    return x?.originalSeq != null ? (this.history().find((h) => h.seq === x.originalSeq) ?? null) : null;
  });

  protected send(input: TerminalInput): void {
    this.run(this.terminal.send(input));
  }

  protected retransmit(seq: number): void {
    this.run(this.terminal.retransmit(seq));
  }

  protected conflicting(seq: number): void {
    this.run(this.terminal.sendConflicting(seq));
  }

  protected fill(s: StandInSuggestion): void {
    this.form()?.fill(s.cardId, s.amount);
    document.getElementById('t-amount')?.focus();
  }

  protected clear(): void {
    this.terminal.clearHistory();
  }

  protected kindLabel(kind: ExchangeKind): string {
    return KIND_LABELS[kind];
  }

  private run(request: Observable<TerminalExchange>): void {
    if (this.busy()) return;
    this.busy.set(true);
    request.subscribe({ next: () => this.busy.set(false), error: () => this.busy.set(false) });
  }
}
