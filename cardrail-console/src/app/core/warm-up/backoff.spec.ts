import { WarmUpTiming, backoffDelayMs, waitUntilAwake } from './backoff';

const TIMING: WarmUpTiming = { deadlineMs: 120_000, attemptTimeoutMs: 15_000, initialDelayMs: 1_000, maxDelayMs: 10_000 };

/** A fake clock: sleeping and slow pings advance it instead of waiting. */
function fakeClock() {
  let t = 0;
  const sleeps: number[] = [];
  return {
    now: () => t,
    advance: (ms: number) => (t += ms),
    sleep: async (ms: number) => {
      sleeps.push(ms);
      t += ms;
    },
    sleeps,
  };
}

describe('warm-up backoff', () => {
  it('doubles the pause from 1 s and caps it at 10 s', () => {
    expect([1, 2, 3, 4, 5, 6, 20].map((n) => backoffDelayMs(n, TIMING))).toEqual([
      1000, 2000, 4000, 8000, 10000, 10000, 10000,
    ]);
  });

  it('succeeds on the first attempt without sleeping', async () => {
    const clock = fakeClock();
    const outcome = await waitUntilAwake({ ping: async () => true, now: clock.now, sleep: clock.sleep }, TIMING);
    expect(outcome).toEqual({ ok: true, attempts: 1, elapsedMs: 0 });
    expect(clock.sleeps).toEqual([]);
  });

  it('retries with backoff until the service answers', async () => {
    const clock = fakeClock();
    let calls = 0;
    const attempts: number[] = [];
    const outcome = await waitUntilAwake(
      {
        ping: async () => ++calls === 4,
        now: clock.now,
        sleep: clock.sleep,
        onAttempt: (n) => attempts.push(n),
      },
      TIMING,
    );
    expect(outcome.ok).toBe(true);
    expect(outcome.attempts).toBe(4);
    expect(attempts).toEqual([1, 2, 3, 4]);
    expect(clock.sleeps).toEqual([1000, 2000, 4000]);
  });

  it('gives up at the deadline (about 120 s) and reports failure', async () => {
    const clock = fakeClock();
    const outcome = await waitUntilAwake(
      {
        // Every attempt hangs until its own timeout.
        ping: async (timeoutMs) => {
          clock.advance(timeoutMs);
          return false;
        },
        now: clock.now,
        sleep: clock.sleep,
      },
      TIMING,
    );
    expect(outcome.ok).toBe(false);
    expect(outcome.elapsedMs).toBeLessThanOrEqual(TIMING.deadlineMs);
    expect(outcome.elapsedMs).toBeGreaterThan(100_000);
  });

  it('never lets one attempt run past the deadline', async () => {
    const clock = fakeClock();
    const timeouts: number[] = [];
    await waitUntilAwake(
      {
        ping: async (timeoutMs) => {
          timeouts.push(timeoutMs);
          clock.advance(timeoutMs);
          return false;
        },
        now: clock.now,
        sleep: clock.sleep,
      },
      TIMING,
    );
    expect(timeouts[0]).toBe(15_000);
    expect(Math.max(...timeouts)).toBeLessThanOrEqual(15_000);
    // The last attempt is cut short to the time left before the deadline.
    expect(timeouts.reduce((a, b) => a + b, 0) + clock.sleeps.reduce((a, b) => a + b, 0)).toBeLessThanOrEqual(
      TIMING.deadlineMs,
    );
  });

  it('stops when cancelled', async () => {
    const clock = fakeClock();
    let cancelled = false;
    const outcome = await waitUntilAwake(
      {
        ping: async () => {
          cancelled = true;
          return false;
        },
        now: clock.now,
        sleep: clock.sleep,
        cancelled: () => cancelled,
      },
      TIMING,
    );
    expect(outcome).toEqual({ ok: false, attempts: 1, elapsedMs: 1000 });
  });
});
