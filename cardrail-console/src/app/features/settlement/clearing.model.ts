/**
 * cardrail-clearing response shapes, mirroring `dev.cardrail.clearing.adapter.web.ClearingRecordView`
 * and `SettlementRunView`. Null fields are omitted by the service, hence the optional members.
 */

export interface ClearingRecord {
  readonly id: number;
  readonly authorizationId: string;
  /** CAPTURE, or REVERSAL for a reversal of a capture. */
  readonly recordType: string;
  readonly token?: string;
  readonly maskedPan?: string;
  readonly issuerBin?: string;
  readonly acquirerId?: string;
  readonly merchantId?: string;
  readonly mcc?: string;
  readonly currency: number;
  readonly amountMinor: number;
  readonly interchangeCategory?: string;
  readonly interchangeFeeMinor: number;
  readonly businessDate: string;
  readonly occurredAt?: string;
  readonly status?: string;
  readonly settlementDate?: string;
  readonly settledAt?: string;
  readonly createdAt?: string;
}

export type PartyType = 'ACQUIRER' | 'ISSUER' | 'NETWORK';

/**
 * Net is what the party receives from (+) or pays to (-) the network's settlement account:
 * acquirer = gross - interchange, issuer = interchange - gross, network = 0.
 */
export interface NetPosition {
  readonly partyType: PartyType;
  readonly partyId: string;
  readonly currency: number;
  readonly recordCount?: number;
  readonly grossMinor?: number;
  readonly interchangeMinor?: number;
  readonly netMinor: number;
}

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface SettlementRun {
  readonly businessDate: string;
  readonly status: RunStatus | string;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  /** Why a FAILED run stopped; POSTing the same date again resumes it. */
  readonly failure?: string;
  readonly recordCount?: number;
  readonly positions: readonly NetPosition[];
  readonly reportUrl?: string;
}

export interface CurrencyTotal {
  readonly currency: number;
  readonly totalMinor: bigint;
  readonly balanced: boolean;
}

const PARTY_ORDER: Record<PartyType, number> = { ACQUIRER: 0, ISSUER: 1, NETWORK: 2 };

export function sortPositions(positions: readonly NetPosition[]): NetPosition[] {
  return [...positions].sort(
    (a, b) =>
      a.currency - b.currency ||
      (PARTY_ORDER[a.partyType] ?? 9) - (PARTY_ORDER[b.partyType] ?? 9) ||
      a.partyId.localeCompare(b.partyId),
  );
}

/**
 * Sum of net positions per currency. Every unit one party pays another receives, so each total
 * must be exactly zero. Summed as BigInt so the check is exact.
 */
export function reconcile(positions: readonly NetPosition[]): CurrencyTotal[] {
  const totals = new Map<number, bigint>();
  for (const p of positions) {
    totals.set(p.currency, (totals.get(p.currency) ?? 0n) + BigInt(p.netMinor));
  }
  return [...totals.entries()]
    .sort(([a], [b]) => a - b)
    .map(([currency, totalMinor]) => ({ currency, totalMinor, balanced: totalMinor === 0n }));
}
