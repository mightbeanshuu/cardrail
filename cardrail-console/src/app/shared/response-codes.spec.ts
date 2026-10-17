import { RESPONSE_CODE_LABELS, RESPONSE_CODE_OPTIONS, responseCodeKind, responseCodeLabel } from './response-codes';

describe('response codes', () => {
  it('labels every code the switch emits', () => {
    expect(RESPONSE_CODE_LABELS).toEqual({
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
    });
  });

  it('handles unknown and missing codes', () => {
    expect(responseCodeLabel('51')).toBe('Insufficient funds');
    expect(responseCodeLabel('77')).toBe('Unknown code');
    expect(responseCodeLabel(null)).toBe('No response code');
  });

  it('classifies codes', () => {
    expect(responseCodeKind('00')).toBe('approved');
    expect(responseCodeKind('51')).toBe('declined');
    expect(responseCodeKind('05')).toBe('declined');
    expect(responseCodeKind('91')).toBe('system');
    expect(responseCodeKind('96')).toBe('system');
  });

  it('offers sorted filter options', () => {
    expect(RESPONSE_CODE_OPTIONS[0]).toEqual({ code: '00', label: '00 Approved' });
    expect(RESPONSE_CODE_OPTIONS.map((o) => o.code)).toEqual(Object.keys(RESPONSE_CODE_LABELS).sort());
  });
});
