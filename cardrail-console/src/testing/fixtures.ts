import { AuthorizationView } from '../app/features/authorizations/authorization.model';

export function authorization(overrides: Partial<AuthorizationView> = {}): AuthorizationView {
  return {
    id: '7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b',
    status: 'AUTHORIZED',
    responseCode: '00',
    authCode: 'A1B2C3',
    standIn: false,
    declineReason: null,
    maskedPan: '555555******4444',
    token: '9999991234567890',
    amountMinor: 25000,
    currency: 356,
    mcc: '5411',
    merchantId: 'MERCHANT000001',
    merchantName: 'Corner Grocery',
    acquirerId: '123456',
    terminalId: 'TERM0001',
    stan: '000123',
    rrn: '629112000123',
    transmissionDateTime: '1018091500',
    capturedAmountMinor: null,
    capturedAt: null,
    reversedAt: null,
    expiredAt: null,
    createdAt: '2026-10-18T09:15:00Z',
    ...overrides,
  };
}
