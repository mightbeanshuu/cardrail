/** The console shows and filters times in UTC so they line up with service logs. */

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function isIsoDate(value: string | null | undefined): value is string {
  if (!value || !DATE_PATTERN.test(value)) {
    return false;
  }
  const d = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().startsWith(value);
}

/** `2026-10-18T09:15:30.123Z` → `2026-10-18 09:15:30`. */
export function formatUtc(instant: string | null | undefined): string {
  if (!instant) {
    return '—';
  }
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) {
    return instant;
  }
  return d.toISOString().slice(0, 19).replace('T', ' ');
}

export function todayUtc(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/** Start of a UTC day as an ISO instant. */
export function startOfDayInstant(date: string): string {
  return `${date}T00:00:00Z`;
}

/** Start of the following UTC day: the exclusive upper bound that makes `date` inclusive. */
export function startOfNextDayInstant(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().replace('.000Z', 'Z');
}
