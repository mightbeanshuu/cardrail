/**
 * Guards that keep full card numbers out of the console. The APIs only ever return masked PANs and
 * tokens; these functions make sure nothing typed or displayed here can carry a full PAN either.
 */

export function luhnValid(digits: string): boolean {
  if (!/^\d+$/.test(digits)) {
    return false;
  }
  let sum = 0;
  let double = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (double) {
      d *= 2;
      if (d > 9) {
        d -= 9;
      }
    }
    sum += d;
    double = !double;
  }
  return sum % 10 === 0;
}

/** True for 12–19 digits (spaces and dashes ignored) that pass the Luhn check. */
export function looksLikePan(value: string | null | undefined): boolean {
  if (!value) {
    return false;
  }
  const digits = value.replace(/[\s-]/g, '');
  return /^\d{12,19}$/.test(digits) && luhnValid(digits);
}

/**
 * Display form of a card number. Masked values from the API pass through unchanged; if a value
 * ever arrives unmasked (10+ digits, no mask characters) it is masked to first 6 and last 4.
 */
export function displayMaskedPan(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const compact = value.replace(/[\s-]/g, '');
  if (/^\d{10,19}$/.test(compact)) {
    const keepFirst = compact.length >= 16 ? 6 : 0;
    return compact.slice(0, keepFirst) + '*'.repeat(compact.length - keepFirst - 4) + compact.slice(-4);
  }
  return value;
}
