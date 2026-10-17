/** Merchants the demo terminal can pretend to be, one per merchant category code (ISO 8583 F18). */
export interface DemoMerchant {
  readonly mcc: string;
  readonly category: string;
  /** F42, at most 15 characters. */
  readonly merchantId: string;
  /** F43, at most 40 characters. */
  readonly merchantName: string;
  /** Shown in the selector when the switch is expected to refuse the category. */
  readonly note?: string;
}

export const DEMO_MERCHANTS: readonly DemoMerchant[] = [
  { mcc: '5411', category: 'Grocery', merchantId: 'CRDEMO-GROC01', merchantName: 'Fresh Mart Pune' },
  { mcc: '5541', category: 'Fuel', merchantId: 'CRDEMO-FUEL01', merchantName: 'Highway Fuel Station' },
  { mcc: '5812', category: 'Restaurant', merchantId: 'CRDEMO-REST01', merchantName: 'Koregaon Kitchen' },
  { mcc: '4511', category: 'Airline', merchantId: 'CRDEMO-AIR001', merchantName: 'Deccan Air' },
  {
    mcc: '7995',
    category: 'Betting',
    merchantId: 'CRDEMO-BET001',
    merchantName: 'Online Betting Ltd',
    note: 'blocked category → 57',
  },
];

export function demoMerchant(mcc: string): DemoMerchant | undefined {
  return DEMO_MERCHANTS.find((m) => m.mcc === mcc);
}
