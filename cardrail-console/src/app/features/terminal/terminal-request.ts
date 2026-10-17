import { DEMO_CURRENCY, DemoCard } from '../../demo/demo-cards';
import { DemoMerchant } from './merchants';

/**
 * Mirrors `dev.cardrail.sw.adapter.web.AuthorizationRequestBody`, the JSON form of an ISO 8583
 * 0100. Formats follow the switch's `RequestValidator` (a mismatch is answered with 30).
 */
export interface AuthorizationRequestBody {
  /** F2. A published test number from DEMO_CARDS; never displayed. */
  readonly pan: string;
  /** F14, YYMM. */
  readonly expiry: string;
  /** F4, minor units. */
  readonly amountMinor: number;
  /** F49, ISO 4217 numeric. */
  readonly currency: number;
  /** F18, 4 digits. */
  readonly mcc: string;
  /** F42, 1 to 15 characters. */
  readonly merchantId: string;
  /** F43, 1 to 40 characters. */
  readonly merchantName: string;
  /** F19, ISO 3166 numeric, 3 digits. */
  readonly merchantCountry: string;
  /** F41, 1 to 8 characters. */
  readonly terminalId: string;
  /** F32, 1 to 11 digits. */
  readonly acquirerId: string;
  /** F11, 6 digits. */
  readonly stan: string;
  /** F37, 12 alphanumerics. */
  readonly rrn: string;
  /** F7, MMDDhhmmss in UTC. */
  readonly transmissionDateTime: string;
  /** F22, 3 digits. */
  readonly posEntryMode: string;
}

/** Mirrors `AuthorizationResponseBody`. `id` is null when nothing was stored (30, and 94). */
export interface AuthorizationResponseBody {
  readonly id: string | null;
  readonly approved: boolean;
  readonly responseCode: string;
  readonly responseCodeName: string;
  readonly authCode: string | null;
  readonly standIn: boolean;
  readonly maskedPan: string | null;
  readonly token: string | null;
}

export const DEMO_ACQUIRER_ID = '40001';
export const DEMO_MERCHANT_COUNTRY = '356'; // India
/** 051: chip read, PIN capable. */
export const DEMO_POS_ENTRY_MODE = '051';

const pad = (n: number, width: number) => String(n).padStart(width, '0');

/** F7: `2026-10-18T09:05:07Z` → `1018090507`. */
export function transmissionDateTime(at: Date): string {
  return (
    pad(at.getUTCMonth() + 1, 2) +
    pad(at.getUTCDate(), 2) +
    pad(at.getUTCHours(), 2) +
    pad(at.getUTCMinutes(), 2) +
    pad(at.getUTCSeconds(), 2)
  );
}

/** Day of the year in UTC, 1 to 366. */
function dayOfYear(at: Date): number {
  const start = Date.UTC(at.getUTCFullYear(), 0, 1);
  const today = Date.UTC(at.getUTCFullYear(), at.getUTCMonth(), at.getUTCDate());
  return Math.round((today - start) / 86_400_000) + 1;
}

/**
 * F37 in the common YDDDhh + STAN layout: last digit of the year, day of the year, UTC hour and the
 * 6-digit STAN, 12 digits in all. `2026-10-18T09:…Z`, `000123` → `629109000123`.
 */
export function retrievalReferenceNumber(at: Date, stan: string): string {
  return String(at.getUTCFullYear() % 10) + pad(dayOfYear(at), 3) + pad(at.getUTCHours(), 2) + stan;
}

/** F11 runs 000001 to 999999 and then starts again at 000001 (000000 is not used). */
export function nextStan(previous: number): number {
  return previous >= 999_999 || previous < 1 ? 1 : previous + 1;
}

const TERMINAL_ALPHABET = '0123456789ABCDEFGHJKLMNPQRSTUVWXYZ';

/** F41 for this browser tab: `WEB` and 5 random characters, 8 in all. */
export function newTerminalId(random: () => number = Math.random): string {
  let id = 'WEB';
  for (let i = 0; i < 5; i++) {
    id += TERMINAL_ALPHABET[Math.floor(random() * TERMINAL_ALPHABET.length)];
  }
  return id;
}

export interface TerminalInput {
  readonly card: DemoCard;
  readonly merchant: DemoMerchant;
  readonly amountMinor: number;
}

/**
 * Builds authorization requests the way a terminal does: one terminal id for its lifetime, a STAN
 * that increases with every new message, and F7 / F37 from the clock at send time. A retransmission
 * reuses the original message unchanged, which is what makes it recognisable to the switch.
 */
export class TerminalRequestBuilder {
  private stan: number;

  constructor(
    readonly terminalId: string = newTerminalId(),
    initialStan: number = 1 + Math.floor(Math.random() * 900_000),
    private readonly clock: () => Date = () => new Date(),
  ) {
    // The first build uses initialStan itself.
    this.stan = initialStan - 1;
  }

  build(input: TerminalInput): AuthorizationRequestBody {
    this.stan = nextStan(this.stan);
    const at = this.clock();
    const stan = pad(this.stan, 6);
    return {
      pan: input.card.pan,
      expiry: input.card.expiry,
      amountMinor: input.amountMinor,
      currency: DEMO_CURRENCY,
      mcc: input.merchant.mcc,
      merchantId: input.merchant.merchantId,
      merchantName: input.merchant.merchantName,
      merchantCountry: DEMO_MERCHANT_COUNTRY,
      terminalId: this.terminalId,
      acquirerId: DEMO_ACQUIRER_ID,
      stan,
      rrn: retrievalReferenceNumber(at, stan),
      transmissionDateTime: transmissionDateTime(at),
      posEntryMode: DEMO_POS_ENTRY_MODE,
    };
  }

  /** The identical message, as a terminal sends it again when no response arrived in time. */
  retransmission(original: AuthorizationRequestBody): AuthorizationRequestBody {
    return { ...original };
  }

  /**
   * Same transmission key (acquirer, STAN, F7, terminal) but a different amount: a conflicting
   * duplicate, which the switch answers with 94.
   */
  conflictingDuplicate(original: AuthorizationRequestBody, deltaMinor = 100): AuthorizationRequestBody {
    return { ...original, amountMinor: original.amountMinor + deltaMinor };
  }
}
