import { HttpClient, HttpContext } from '@angular/common/http';
import { DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { Observable, catchError, firstValueFrom, from, map, of, timeout } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { QUIET_ERRORS, SKIP_WARM_UP } from '../http/http-context';
import { DEFAULT_WARM_UP_TIMING, WarmUpTiming, waitUntilAwake } from './backoff';

export type ServiceId = 'switch' | 'clearing';

/** checking: first health check in flight; waking: it failed or is slow; ready; failed: gave up. */
export type WarmUpStatus = 'idle' | 'checking' | 'waking' | 'ready' | 'failed';

export interface ServiceWarmUp {
  readonly id: ServiceId;
  readonly label: string;
  readonly status: WarmUpStatus;
  readonly attempts: number;
}

export interface WarmUpOptions extends WarmUpTiming {
  /** A first check slower than this counts as waking, so the banner appears. */
  readonly slowAfterMs: number;
  readonly now: () => number;
  readonly sleep: (ms: number) => Promise<void>;
}

export const WARM_UP_OPTIONS = new InjectionToken<WarmUpOptions>('WARM_UP_OPTIONS', {
  providedIn: 'root',
  factory: () => ({
    ...DEFAULT_WARM_UP_TIMING,
    slowAfterMs: 2_000,
    now: () => Date.now(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  }),
});

const LABELS: Record<ServiceId, string> = { switch: 'Switch', clearing: 'Clearing' };
const IDS: readonly ServiceId[] = ['switch', 'clearing'];

/**
 * Wakes the demo services when the app loads. The landing page renders immediately; API requests
 * to a service are held by the warm-up interceptor until its health check answers or gives up.
 */
@Injectable({ providedIn: 'root' })
export class WarmUpService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG).warmUp;
  private readonly options = inject(WARM_UP_OPTIONS);

  private readonly _services = signal<Record<ServiceId, ServiceWarmUp>>({
    switch: { id: 'switch', label: LABELS.switch, status: 'idle', attempts: 0 },
    clearing: { id: 'clearing', label: LABELS.clearing, status: 'idle', attempts: 0 },
  });
  private readonly startedAt = signal<number | null>(null);
  private readonly tick = signal(0);
  private readonly settled = new Map<ServiceId, Promise<void>>();
  private ticker: ReturnType<typeof setInterval> | null = null;
  private generation = 0;
  private destroyed = false;

  readonly services = computed(() => IDS.map((id) => this._services()[id]));
  readonly enabled = this.config.enabled;
  /** True while any service is still being woken (after the first quick check failed or stalled). */
  readonly waking = computed(() => this.services().some((s) => s.status === 'waking'));
  readonly failed = computed(() => this.services().some((s) => s.status === 'failed'));
  readonly allReady = computed(() => this.services().every((s) => s.status === 'ready'));
  readonly elapsedSeconds = computed(() => {
    this.tick();
    const start = this.startedAt();
    return start == null ? 0 : Math.max(0, Math.floor((this.options.now() - start) / 1000));
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.stopTicker();
    });
  }

  status(id: ServiceId): WarmUpStatus {
    return this._services()[id].status;
  }

  /** Starts waking both services. Safe to call more than once; later calls do nothing. */
  start(): void {
    if (!this.enabled || this.startedAt() != null) return;
    this.run();
  }

  /** After a failure: start over with a fresh deadline. */
  retry(): void {
    if (!this.enabled) return;
    this.run();
  }

  /**
   * Emits once the service is ready or warm-up has given up (the request then goes ahead and fails
   * or succeeds on its own). Emits immediately when warm-up is off or was never started.
   */
  whenSettled(id: ServiceId): Observable<void> {
    const pending = this.settled.get(id);
    return pending ? from(pending) : of(undefined);
  }

  private run(): void {
    const generation = ++this.generation;
    this.startedAt.set(this.options.now());
    this.startTicker();
    for (const id of IDS) {
      this.update(id, { status: 'checking', attempts: 0 });
      this.settled.set(id, this.wake(id, generation));
    }
  }

  private async wake(id: ServiceId, generation: number): Promise<void> {
    const url = id === 'switch' ? this.config.switchHealthUrl : this.config.clearingHealthUrl;
    const slow = setTimeout(() => {
      if (generation === this.generation && this.status(id) === 'checking') this.update(id, { status: 'waking' });
    }, this.options.slowAfterMs);
    const outcome = await waitUntilAwake(
      {
        ping: (ms) => this.ping(url, ms),
        now: this.options.now,
        sleep: this.options.sleep,
        cancelled: () => this.destroyed || generation !== this.generation,
        onAttempt: (attempt) => {
          if (generation !== this.generation) return;
          this.update(id, { attempts: attempt, ...(attempt > 1 ? { status: 'waking' as const } : {}) });
        },
      },
      this.options,
    );
    clearTimeout(slow);
    if (generation !== this.generation) return;
    this.update(id, { status: outcome.ok ? 'ready' : 'failed' });
    if (this.services().every((s) => s.status === 'ready' || s.status === 'failed')) this.stopTicker();
  }

  private ping(url: string, timeoutMs: number): Promise<boolean> {
    const context = new HttpContext().set(QUIET_ERRORS, true).set(SKIP_WARM_UP, true);
    return firstValueFrom(
      this.http.get(url, { context }).pipe(
        timeout(timeoutMs),
        map(() => true),
        catchError(() => of(false)),
      ),
    );
  }

  private update(id: ServiceId, patch: Partial<ServiceWarmUp>): void {
    this._services.update((all) => ({ ...all, [id]: { ...all[id], ...patch } }));
  }

  private startTicker(): void {
    this.stopTicker();
    if (typeof setInterval === 'function') {
      this.ticker = setInterval(() => this.tick.update((n) => n + 1), 1000);
    }
  }

  private stopTicker(): void {
    if (this.ticker != null) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
    this.tick.update((n) => n + 1);
  }
}
