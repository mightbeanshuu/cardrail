import { formatMinor } from '../../shared/money';
import { responseCodeLabel } from '../../shared/response-codes';
import { TerminalExchange } from './terminal.service';

/** Issuer time limit configured in the switch (`cardrail.issuer.resilience.timeout`). */
export const ISSUER_TIMEOUT_MS = 1500;

/**
 * One plain sentence on why the switch answered the way it did, for the receipt. `original` is the
 * exchange a retransmission repeats, when it is still in the history.
 */
export function explainExchange(x: TerminalExchange, original: TerminalExchange | null = null, locale = 'en-IN'): string {
  if (x.error) {
    if (x.error.isRateLimited) return x.error.detail ?? 'Too many requests from your address. Wait a few seconds and try again.';
    if (x.error.status === 0) return 'No response arrived. The switch may still be waking up, or the connection failed.';
    return `The switch could not process the request (HTTP ${x.error.status}: ${x.error.title}).`;
  }
  const r = x.response;
  if (!r) return '';
  const amount = formatMinor(x.amountMinor, x.currency, locale);
  const limit = formatMinor(x.card.standInLimitMinor, x.currency, locale);

  if (x.kind === 'retransmission' && r.responseCode !== '94') {
    const same = original?.response?.id != null && original.response.id === r.id;
    return same
      ? 'Retransmission recognised: the switch matched the transmission key and returned the stored answer, with the same authorization id. No second hold was placed.'
      : 'Retransmission: the switch matched the transmission key and returned the stored answer instead of authorizing twice.';
  }

  switch (r.responseCode) {
    case '00':
      return r.standIn
        ? `Approved by stand-in processing: the issuer did not answer in time, and ${amount} is within this card range's stand-in limit of ${limit}. The switch records an advice for the issuer.`
        : `Approved by the issuer. ${amount} is now held on the card until it is captured, reversed or expires.`;
    case '91':
      return `The issuer did not answer in time and ${amount} is above the stand-in limit of ${limit}, so the switch declined instead of approving on the issuer's behalf.`;
    case '94':
      return 'Same transmission key (acquirer, STAN, transmission time, terminal) as an earlier request, but a different amount. The switch refuses it rather than guess which message is right.';
    case '51':
      return 'Declined by the issuer: the available balance does not cover the amount.';
    case '05':
      return 'Declined by the issuer: the account is blocked.';
    case '14':
      return 'Declined by the issuer: it has no account for this card number.';
    case '57':
      return `Declined by the switch's risk rules before the issuer was asked: merchant category ${x.merchant.mcc} (${x.merchant.category.toLowerCase()}) is blocked for this card.`;
    case '61':
      return "Declined by the switch's risk rules: the amount is above the per-transaction limit.";
    case '65':
      return "Declined by the switch's risk rules: too many authorizations on this card in the last hour.";
    case '13':
      return 'Declined: the amount is outside the range the switch accepts.';
    case '30':
      return 'Format error: a field in the message did not have the format ISO 8583 requires.';
    default:
      return `${r.responseCode} ${responseCodeLabel(r.responseCode)}.`;
  }
}
