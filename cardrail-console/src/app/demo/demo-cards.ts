/**
 * The test cards offered by the public demo's terminal. This is the one place to change them; the
 * switch's `demo` profile must open matching issuer accounts (see cardrail-console/README.md).
 *
 * Every number is a published Mastercard test card number (Luhn-valid, in the 51-55 or 2221-2720
 * ranges, never issued to a cardholder). The console sends `pan` to the switch in the request body
 * and never renders it: the UI shows only `maskedPan(card)`. There is no field anywhere in the
 * console where a card number can be typed.
 *
 * Stand-in limits mirror `stand_in_limit` in the switch's V6 migration (INR): BIN prefix 555555
 * 5,000.00; prefix 5 2,500.00; any other card 1,000.00.
 */
export interface DemoCard {
  /** Stable key for forms, history and tests. */
  readonly id: string;
  /** Published test number. Sent to the switch, never displayed. */
  readonly pan: string;
  /** ISO 8583 field 14, YYMM. */
  readonly expiry: string;
  /** What the visitor sees next to the masked number. */
  readonly label: string;
  /** One line on what to expect from this card. */
  readonly hint: string;
  /** Response code the issuer is expected to answer with when it is healthy; '00' for funded cards. */
  readonly expectedCode: string;
  /** Opening balance the demo profile gives the account (INR minor units); null when no account exists. */
  readonly balanceMinor: number | null;
  /** The switch's stand-in limit for this card's BIN range (INR minor units). */
  readonly standInLimitMinor: number;
}

export const DEMO_CURRENCY = 356; // INR

export const DEMO_CARDS: readonly DemoCard[] = [
  {
    id: 'funded',
    pan: '5555555555554444',
    expiry: '3012',
    label: 'Funded card',
    hint: 'Balance 50,000.00. Stand-in limit 5,000.00.',
    expectedCode: '00',
    balanceMinor: 5_000_000,
    standInLimitMinor: 500_000,
  },
  {
    id: 'debit',
    pan: '5200828282828210',
    expiry: '3012',
    label: 'Funded debit card',
    hint: 'Balance 25,000.00. Stand-in limit 2,500.00.',
    expectedCode: '00',
    balanceMinor: 2_500_000,
    standInLimitMinor: 250_000,
  },
  {
    id: 'two-series',
    pan: '2223003122003222',
    expiry: '3012',
    label: 'Funded card, 2-series BIN',
    hint: 'Balance 10,000.00. Stand-in limit 1,000.00 (no 5-series limit applies).',
    expectedCode: '00',
    balanceMinor: 1_000_000,
    standInLimitMinor: 100_000,
  },
  {
    id: 'low-balance',
    pan: '5105105105105100',
    expiry: '3012',
    label: 'Low balance → 51',
    hint: 'Balance 500.00. Anything above it is declined with 51.',
    expectedCode: '51',
    balanceMinor: 50_000,
    standInLimitMinor: 250_000,
  },
  {
    id: 'blocked',
    pan: '5425233430109903',
    expiry: '3012',
    label: 'Blocked → 05',
    hint: 'The issuer has blocked the account. Every request is declined with 05.',
    expectedCode: '05',
    balanceMinor: 1_000_000,
    standInLimitMinor: 250_000,
  },
  {
    id: 'no-account',
    pan: '2223000048410010',
    expiry: '3012',
    label: 'No account → 14',
    hint: 'A valid number the issuer has no account for. Declined with 14.',
    expectedCode: '14',
    balanceMinor: null,
    standInLimitMinor: 100_000,
  },
];

/** First 6 and last 4, the same form the switch returns: `555555******4444`. */
export function maskedPan(card: Pick<DemoCard, 'pan'>): string {
  const pan = card.pan;
  return pan.slice(0, 6) + '*'.repeat(pan.length - 10) + pan.slice(-4);
}

/** `··4444`, for compact labels. */
export function panSuffix(card: Pick<DemoCard, 'pan'>): string {
  return `··${card.pan.slice(-4)}`;
}

export function demoCard(id: string): DemoCard | undefined {
  return DEMO_CARDS.find((c) => c.id === id);
}
