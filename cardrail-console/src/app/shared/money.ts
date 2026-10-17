/**
 * Money helpers. Amounts are integers in minor units (paise, cents) everywhere in cardrail.
 * Conversions here are done on decimal strings and integers, never by multiplying or dividing floats.
 */

export interface CurrencyInfo {
  /** ISO 4217 numeric code, as carried in ISO 8583 field 49. */
  readonly numeric: number;
  readonly alpha: string;
  /** Number of minor-unit digits (2 for INR, 0 for JPY). */
  readonly exponent: number;
}

const CURRENCIES: ReadonlyMap<number, CurrencyInfo> = new Map(
  [
    { numeric: 356, alpha: 'INR', exponent: 2 },
    { numeric: 840, alpha: 'USD', exponent: 2 },
    { numeric: 978, alpha: 'EUR', exponent: 2 },
    { numeric: 826, alpha: 'GBP', exponent: 2 },
    { numeric: 702, alpha: 'SGD', exponent: 2 },
    { numeric: 784, alpha: 'AED', exponent: 2 },
    { numeric: 392, alpha: 'JPY', exponent: 0 },
  ].map((c) => [c.numeric, c] as const),
);

export function currencyInfo(numeric: number | null | undefined): CurrencyInfo | undefined {
  return numeric == null ? undefined : CURRENCIES.get(numeric);
}

/** Exponent for a currency, defaulting to 2 for codes this console does not know. */
export function currencyExponent(numeric: number | null | undefined): number {
  return currencyInfo(numeric)?.exponent ?? 2;
}

/** `20010, 2` → `"200.10"`; `-5, 2` → `"-0.05"`; `1500, 0` → `"1500"`. */
export function minorToDecimalString(minor: number | bigint, exponent: number): string {
  if (typeof minor === 'number' && !Number.isSafeInteger(minor)) {
    throw new RangeError(`amount ${minor} is not a safe integer number of minor units`);
  }
  const negative = minor < 0;
  const digits = (negative ? -BigInt(minor) : BigInt(minor)).toString();
  if (exponent === 0) {
    return (negative ? '-' : '') + digits;
  }
  const padded = digits.padStart(exponent + 1, '0');
  const whole = padded.slice(0, padded.length - exponent);
  const fraction = padded.slice(padded.length - exponent);
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}

const formatters = new Map<string, Intl.NumberFormat>();

function formatter(locale: string, info: CurrencyInfo): Intl.NumberFormat {
  const key = `${locale}|${info.alpha}`;
  let f = formatters.get(key);
  if (!f) {
    f = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: info.alpha,
      minimumFractionDigits: info.exponent,
      maximumFractionDigits: info.exponent,
    });
    formatters.set(key, f);
  }
  return f;
}

/**
 * Formats minor units with the currency symbol, e.g. `25000, 356` → `₹250.00` (en-IN).
 * The decimal string is handed to Intl as a string, so no float rounding is involved.
 * Unknown currencies are shown as the raw minor amount with the numeric code.
 */
export function formatMinor(
  minor: number | null | undefined,
  currencyNumeric: number | null | undefined,
  locale = 'en-IN',
): string {
  if (minor == null) {
    return '—';
  }
  const info = currencyInfo(currencyNumeric);
  if (!info) {
    return `${minor} minor units (currency ${currencyNumeric ?? '?'})`;
  }
  const decimal = minorToDecimalString(minor, info.exponent) as Intl.StringNumericLiteral;
  return formatter(locale, info).format(decimal);
}

export type AmountParseError = 'empty' | 'negative' | 'invalid' | 'too-many-decimals' | 'too-large';

export type AmountParseResult =
  | { readonly ok: true; readonly minor: number }
  | { readonly ok: false; readonly error: AmountParseError };

const AMOUNT_PATTERN = /^(\d+)(?:\.(\d+))?$/;

/**
 * Parses an amount typed in major units into minor units by string manipulation:
 * `"200.10", 2` → `20010`; `"200.1", 2` → `20010`; `"1.234", 2` → too-many-decimals.
 * Only digits and an optional dot are accepted (no signs, grouping separators or exponents).
 */
export function parseMajorToMinor(input: string | null | undefined, exponent: number): AmountParseResult {
  const s = (input ?? '').trim();
  if (s === '') {
    return { ok: false, error: 'empty' };
  }
  if (s.startsWith('-')) {
    return { ok: false, error: 'negative' };
  }
  const match = AMOUNT_PATTERN.exec(s);
  if (!match) {
    return { ok: false, error: 'invalid' };
  }
  const whole = match[1];
  const fraction = match[2] ?? '';
  if (fraction.length > exponent) {
    return { ok: false, error: 'too-many-decimals' };
  }
  const minor = BigInt(whole + fraction.padEnd(exponent, '0'));
  if (minor > BigInt(Number.MAX_SAFE_INTEGER)) {
    return { ok: false, error: 'too-large' };
  }
  return { ok: true, minor: Number(minor) };
}

export function describeAmountParseError(error: AmountParseError, exponent: number): string {
  switch (error) {
    case 'empty':
      return 'Enter an amount.';
    case 'negative':
      return 'Amount cannot be negative.';
    case 'invalid':
      return exponent === 0
        ? 'Use digits only, for example 1500.'
        : 'Use digits and an optional dot, for example 200.10.';
    case 'too-many-decimals':
      return exponent === 0
        ? 'This currency has no minor units; enter a whole number.'
        : `Use at most ${exponent} decimal places.`;
    case 'too-large':
      return 'Amount is too large.';
  }
}
