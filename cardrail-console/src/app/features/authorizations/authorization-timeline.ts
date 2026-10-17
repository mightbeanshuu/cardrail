import { formatMinor } from '../../shared/money';
import { responseCodeLabel } from '../../shared/response-codes';
import { AuthorizationView } from './authorization.model';

export interface TimelineStep {
  readonly label: string;
  readonly at: string | null;
  readonly detail: string;
  readonly tone: 'done' | 'failed' | 'pending';
}

/** The lifecycle events derivable from an AuthorizationView, oldest first. */
export function authorizationTimeline(a: AuthorizationView, locale = 'en-IN'): TimelineStep[] {
  const decidedBy = a.standIn ? 'stand-in processing (STIP)' : 'issuer';
  const code = a.responseCode ? `${a.responseCode} ${responseCodeLabel(a.responseCode)}` : 'no response code';
  const steps: TimelineStep[] = [];

  if (a.status === 'PENDING') {
    steps.push({ label: 'Received', at: a.createdAt, detail: 'Awaiting a decision.', tone: 'pending' });
    return steps;
  }

  const approved = a.responseCode === '00';
  steps.push({
    label: approved ? 'Approved' : 'Declined',
    at: a.createdAt,
    detail: approved
      ? `${code}, auth code ${a.authCode ?? '—'}, by ${decidedBy}. Hold placed for ${formatMinor(a.amountMinor, a.currency, locale)}.`
      : `${code}, by ${decidedBy}${a.declineReason ? `. Reason: ${a.declineReason}` : ''}.`,
    tone: approved ? 'done' : 'failed',
  });

  const later: TimelineStep[] = [];
  if (a.capturedAt) {
    const captured = a.capturedAmountMinor ?? a.amountMinor;
    const released = a.amountMinor - captured;
    later.push({
      label: 'Captured',
      at: a.capturedAt,
      detail:
        released > 0
          ? `${formatMinor(captured, a.currency, locale)} captured; ${formatMinor(released, a.currency, locale)} released.`
          : `${formatMinor(captured, a.currency, locale)} captured in full.`,
      tone: 'done',
    });
  }
  if (a.reversedAt) {
    later.push({ label: 'Reversed', at: a.reversedAt, detail: 'Hold released to the cardholder.', tone: 'done' });
  }
  if (a.expiredAt) {
    later.push({ label: 'Hold expired', at: a.expiredAt, detail: 'Not captured in time; hold released.', tone: 'done' });
  }
  later.sort((x, y) => (x.at ?? '').localeCompare(y.at ?? ''));
  steps.push(...later);

  if (a.status === 'AUTHORIZED') {
    steps.push({ label: 'Awaiting capture or reversal', at: null, detail: 'The hold is open.', tone: 'pending' });
  }
  return steps;
}
