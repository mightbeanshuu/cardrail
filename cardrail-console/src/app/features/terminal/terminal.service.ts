import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Observable, catchError, defer, map, of, switchMap } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError } from '../../core/errors/api-error';
import { handledStatuses } from '../../core/http/http-context';
import { WarmUpService } from '../../core/warm-up/warm-up.service';
import { DEMO_CURRENCY, maskedPan } from '../../demo/demo-cards';
import { asApiError } from '../../shared/load-state';
import { DemoMerchant } from './merchants';
import {
  AuthorizationRequestBody,
  AuthorizationResponseBody,
  TerminalInput,
  TerminalRequestBuilder,
} from './terminal-request';

/** new: a fresh message; retransmission: the identical message again; conflicting: same key, other amount. */
export type ExchangeKind = 'new' | 'retransmission' | 'conflicting';

/**
 * One request/response pair as the terminal saw it. Deliberately has no PAN: the request body that
 * carries it stays inside TerminalService, and only the masked form is kept here for display.
 */
export interface TerminalExchange {
  readonly seq: number;
  readonly kind: ExchangeKind;
  /** For a retransmission or conflicting duplicate: the exchange it repeats. */
  readonly originalSeq: number | null;
  readonly card: {
    readonly id: string;
    readonly label: string;
    readonly maskedPan: string;
    readonly standInLimitMinor: number;
  };
  readonly merchant: DemoMerchant;
  readonly amountMinor: number;
  readonly currency: number;
  readonly terminalId: string;
  readonly stan: string;
  readonly rrn: string;
  readonly transmissionDateTime: string;
  readonly sentAt: string;
  /** Measured in the browser from send to response, so it includes the network. */
  readonly latencyMs: number;
  readonly response: AuthorizationResponseBody | null;
  readonly error: ApiError | null;
}

export const TERMINAL_REQUEST_BUILDER = new InjectionToken<TerminalRequestBuilder>('TERMINAL_REQUEST_BUILDER', {
  providedIn: 'root',
  factory: () => new TerminalRequestBuilder(),
});

/** Monotonic clock for latency. */
export const TERMINAL_CLOCK = new InjectionToken<() => number>('TERMINAL_CLOCK', {
  providedIn: 'root',
  factory: () => () => (typeof performance !== 'undefined' ? performance.now() : Date.now()),
});

const HISTORY_LIMIT = 50;

/** The simulated card terminal: builds 0100 requests, sends them to the switch, keeps this session's history. */
@Injectable({ providedIn: 'root' })
export class TerminalService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).switchBaseUrl}/v1/authorizations`;
  private readonly builder = inject(TERMINAL_REQUEST_BUILDER);
  private readonly clock = inject(TERMINAL_CLOCK);
  private readonly warmUp = inject(WarmUpService);
  private readonly requests = new Map<number, { body: AuthorizationRequestBody; input: TerminalInput }>();
  private seq = 0;

  private readonly _history = signal<readonly TerminalExchange[]>([]);
  /** Newest first. Lives as long as the tab. */
  readonly history = this._history.asReadonly();

  readonly terminalId = this.builder.terminalId;

  send(input: TerminalInput): Observable<TerminalExchange> {
    return defer(() => this.exchange(this.builder.build(input), input, 'new', null));
  }

  /** Sends the identical message again; the switch replays its stored answer. */
  retransmit(originalSeq: number): Observable<TerminalExchange> {
    return defer(() => {
      const original = this.requireRequest(originalSeq);
      return this.exchange(this.builder.retransmission(original.body), original.input, 'retransmission', originalSeq);
    });
  }

  /** Same transmission key, different amount: the switch answers 94. */
  sendConflicting(originalSeq: number): Observable<TerminalExchange> {
    return defer(() => {
      const original = this.requireRequest(originalSeq);
      const body = this.builder.conflictingDuplicate(original.body);
      return this.exchange(body, { ...original.input, amountMinor: body.amountMinor }, 'conflicting', originalSeq);
    });
  }

  clearHistory(): void {
    this._history.set([]);
    this.requests.clear();
  }

  private requireRequest(seq: number) {
    const original = this.requests.get(seq);
    if (!original) throw new Error(`no request #${seq} in this session`);
    return original;
  }

  private exchange(
    body: AuthorizationRequestBody,
    input: TerminalInput,
    kind: ExchangeKind,
    originalSeq: number | null,
  ): Observable<TerminalExchange> {
    // Wait for a sleeping backend first, so the latency shown is the switch's, not the wake-up's.
    return this.warmUp.whenSettled('switch').pipe(
      switchMap(() => {
        const started = this.clock();
        const sentAt = new Date().toISOString();
        return this.http
          .post<AuthorizationResponseBody>(this.url, body, { context: handledStatuses(429) })
          .pipe(
            map((response) => ({ response, error: null as ApiError | null })),
            catchError((e: unknown) => of({ response: null, error: asApiError(e) })),
            map(({ response, error }) => this.record(body, input, kind, originalSeq, sentAt, this.clock() - started, response, error)),
          );
      }),
    );
  }

  private record(
    body: AuthorizationRequestBody,
    input: TerminalInput,
    kind: ExchangeKind,
    originalSeq: number | null,
    sentAt: string,
    latencyMs: number,
    response: AuthorizationResponseBody | null,
    error: ApiError | null,
  ): TerminalExchange {
    const seq = ++this.seq;
    this.requests.set(seq, { body, input });
    const exchange: TerminalExchange = {
      seq,
      kind,
      originalSeq,
      card: {
        id: input.card.id,
        label: input.card.label,
        maskedPan: maskedPan(input.card),
        standInLimitMinor: input.card.standInLimitMinor,
      },
      merchant: input.merchant,
      amountMinor: body.amountMinor,
      currency: DEMO_CURRENCY,
      terminalId: body.terminalId,
      stan: body.stan,
      rrn: body.rrn,
      transmissionDateTime: body.transmissionDateTime,
      sentAt,
      latencyMs: Math.max(0, Math.round(latencyMs)),
      response,
      error,
    };
    this._history.update((h) => [exchange, ...h].slice(0, HISTORY_LIMIT));
    return exchange;
  }
}
