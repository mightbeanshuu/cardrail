import { AuthorizationStatus } from '../../shared/status-chip.component';

export type { AuthorizationStatus };

export const AUTHORIZATION_STATUSES: readonly AuthorizationStatus[] = [
  'AUTHORIZED',
  'CAPTURED',
  'DECLINED',
  'REVERSED',
  'EXPIRED',
  'PENDING',
];

/** Mirrors `dev.cardrail.sw.adapter.web.AuthorizationView`. Instants are ISO-8601 strings. */
export interface AuthorizationView {
  readonly id: string;
  readonly status: AuthorizationStatus;
  readonly responseCode: string | null;
  readonly authCode: string | null;
  readonly standIn: boolean;
  readonly declineReason: string | null;
  /** First 6 + last 4. The console never receives or shows a full PAN. */
  readonly maskedPan: string;
  readonly token: string | null;
  readonly amountMinor: number;
  /** ISO 4217 numeric, e.g. 356. */
  readonly currency: number;
  readonly mcc: string;
  readonly merchantId: string;
  readonly merchantName: string | null;
  readonly acquirerId: string;
  readonly terminalId: string;
  readonly stan: string;
  readonly rrn: string;
  /** ISO 8583 field 7, MMDDhhmmss. */
  readonly transmissionDateTime: string | null;
  readonly capturedAmountMinor: number | null;
  readonly capturedAt: string | null;
  readonly reversedAt: string | null;
  readonly expiredAt: string | null;
  readonly createdAt: string;
}

/** Mirrors `dev.cardrail.sw.application.ReversalResult`. */
export interface ReversalResult {
  readonly authorizationId: string;
  readonly outcome: 'REVERSED' | 'NOTHING_TO_REVERSE';
  readonly status: AuthorizationStatus;
  readonly releasedMinor: number;
  readonly reversedAt: string | null;
}

/** Mirrors `dev.cardrail.sw.application.CaptureResult`. */
export interface CaptureResult {
  readonly authorizationId: string;
  readonly capturedMinor: number;
  readonly releasedMinor: number;
  readonly capturedAt: string;
}
