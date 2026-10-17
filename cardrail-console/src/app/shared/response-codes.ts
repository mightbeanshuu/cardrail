/** ISO 8583 field 39 values the switch emits (docs/SPEC.md), with labels for people. */
export const RESPONSE_CODE_LABELS: Readonly<Record<string, string>> = {
  '00': 'Approved',
  '05': 'Do not honor',
  '12': 'Invalid transaction',
  '13': 'Invalid amount',
  '14': 'Invalid card number',
  '30': 'Format error',
  '51': 'Insufficient funds',
  '54': 'Expired card',
  '57': 'Not permitted',
  '61': 'Exceeds amount limit',
  '65': 'Exceeds frequency limit',
  '91': 'Issuer unavailable',
  '94': 'Duplicate',
  '96': 'System malfunction',
};

/** approved; declined for a cardholder/issuer reason; system for switch, format or availability failures. */
export type ResponseCodeKind = 'approved' | 'declined' | 'system';

const SYSTEM_CODES = new Set(['30', '91', '94', '96']);

export function responseCodeLabel(code: string | null | undefined): string {
  if (code == null || code === '') {
    return 'No response code';
  }
  return RESPONSE_CODE_LABELS[code] ?? 'Unknown code';
}

export function responseCodeKind(code: string | null | undefined): ResponseCodeKind {
  if (code === '00') {
    return 'approved';
  }
  return code != null && SYSTEM_CODES.has(code) ? 'system' : 'declined';
}

export const RESPONSE_CODE_OPTIONS: readonly { code: string; label: string }[] = Object.keys(
  RESPONSE_CODE_LABELS,
)
  .sort()
  .map((code) => ({ code, label: `${code} ${RESPONSE_CODE_LABELS[code]}` }));
