/**
 * Waking a sleeping service: ping its health endpoint until it answers, backing off between
 * attempts, and give up at a deadline. Kept free of Angular and timers so it can be tested with a
 * fake clock.
 */

export interface WarmUpTiming {
  /** Give up once this much time has passed since the first attempt. */
  readonly deadlineMs: number;
  /** One attempt is abandoned after this long (a waking instance can hold a request for a while). */
  readonly attemptTimeoutMs: number;
  /** Pause after the first failed attempt; doubles after each further one. */
  readonly initialDelayMs: number;
  /** Upper bound for the pause between attempts. */
  readonly maxDelayMs: number;
}

export const DEFAULT_WARM_UP_TIMING: WarmUpTiming = {
  deadlineMs: 120_000,
  attemptTimeoutMs: 15_000,
  initialDelayMs: 1_000,
  maxDelayMs: 10_000,
};

/** Pause after failed attempt number `attempt` (1-based): 1 s, 2 s, 4 s, 8 s, then 10 s. */
export function backoffDelayMs(attempt: number, timing: Pick<WarmUpTiming, 'initialDelayMs' | 'maxDelayMs'>): number {
  const exponent = Math.max(0, Math.min(attempt - 1, 30));
  return Math.min(timing.maxDelayMs, timing.initialDelayMs * 2 ** exponent);
}

export interface WarmUpOutcome {
  readonly ok: boolean;
  readonly attempts: number;
  readonly elapsedMs: number;
}

export interface WarmUpDeps {
  /** One health check. Resolves true when the service answered healthy, false otherwise; never rejects. */
  readonly ping: (timeoutMs: number) => Promise<boolean>;
  readonly now: () => number;
  readonly sleep: (ms: number) => Promise<void>;
  /** Called before each attempt with its 1-based number. */
  readonly onAttempt?: (attempt: number) => void;
  /** Stops the loop early (for example when the app is torn down). */
  readonly cancelled?: () => boolean;
}

export async function waitUntilAwake(deps: WarmUpDeps, timing: WarmUpTiming = DEFAULT_WARM_UP_TIMING): Promise<WarmUpOutcome> {
  const start = deps.now();
  let attempts = 0;
  for (;;) {
    const remaining = timing.deadlineMs - (deps.now() - start);
    if (remaining <= 0 || deps.cancelled?.()) {
      return { ok: false, attempts, elapsedMs: deps.now() - start };
    }
    attempts++;
    deps.onAttempt?.(attempts);
    if (await deps.ping(Math.min(timing.attemptTimeoutMs, remaining))) {
      return { ok: true, attempts, elapsedMs: deps.now() - start };
    }
    const delay = backoffDelayMs(attempts, timing);
    if (deps.now() - start + delay >= timing.deadlineMs) {
      return { ok: false, attempts, elapsedMs: deps.now() - start };
    }
    await deps.sleep(delay);
  }
}
