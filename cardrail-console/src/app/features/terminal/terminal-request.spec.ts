import { DEMO_CARDS } from '../../demo/demo-cards';
import { DEMO_MERCHANTS } from './merchants';
import {
  AuthorizationRequestBody,
  TerminalRequestBuilder,
  newTerminalId,
  nextStan,
  retrievalReferenceNumber,
  transmissionDateTime,
} from './terminal-request';

/** The fields of the switch's AuthorizationRequestBody record, in declaration order. */
const DTO_FIELDS = [
  'pan',
  'expiry',
  'amountMinor',
  'currency',
  'mcc',
  'merchantId',
  'merchantName',
  'merchantCountry',
  'terminalId',
  'acquirerId',
  'stan',
  'rrn',
  'transmissionDateTime',
  'posEntryMode',
];

/** The checks of the switch's RequestValidator.formatError, which answers 30 when one fails. */
function formatErrors(r: AuthorizationRequestBody): string[] {
  const errors: string[] = [];
  if (!/^\d{6}$/.test(r.stan)) errors.push('stan');
  if (!/^\d{10}$/.test(r.transmissionDateTime)) errors.push('transmission-datetime');
  else {
    const [mm, dd, hh, mi, ss] = [0, 2, 4, 6, 8].map((i) => Number(r.transmissionDateTime.slice(i, i + 2)));
    if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh > 23 || mi > 59 || ss > 59) errors.push('transmission-datetime');
  }
  if (!/^\d{1,11}$/.test(r.acquirerId)) errors.push('acquirer-id');
  if (r.terminalId.length < 1 || r.terminalId.length > 8) errors.push('terminal-id');
  if (!/^[A-Za-z0-9]{12}$/.test(r.rrn)) errors.push('rrn');
  if (!/^\d{2}(0[1-9]|1[0-2])$/.test(r.expiry)) errors.push('expiry');
  if (!/^\d{4}$/.test(r.mcc)) errors.push('mcc');
  if (!/^\d{3}$/.test(r.merchantCountry) || !/^\d{3}$/.test(r.posEntryMode)) errors.push('country-or-pos');
  if (r.merchantId.length < 1 || r.merchantId.length > 15 || r.merchantName.length < 1 || r.merchantName.length > 40) {
    errors.push('merchant');
  }
  if (r.currency < 1 || r.currency > 999) errors.push('currency');
  return errors;
}

const input = { card: DEMO_CARDS[0], merchant: DEMO_MERCHANTS[0], amountMinor: 25000 };

describe('terminal request builder', () => {
  it('formats F7 as MMDDhhmmss in UTC', () => {
    expect(transmissionDateTime(new Date('2026-10-18T09:05:07Z'))).toBe('1018090507');
    expect(transmissionDateTime(new Date('2026-01-02T23:59:58.900Z'))).toBe('0102235958');
  });

  it('formats F37 as YDDDhh + STAN (12 digits)', () => {
    expect(retrievalReferenceNumber(new Date('2026-10-18T09:15:00Z'), '000123')).toBe('629109000123');
    expect(retrievalReferenceNumber(new Date('2026-01-01T00:00:00Z'), '999999')).toBe('600100999999');
    expect(retrievalReferenceNumber(new Date('2028-12-31T23:00:00Z'), '000001')).toBe('836623000001');
  });

  it('wraps the STAN from 999999 back to 000001', () => {
    expect(nextStan(1)).toBe(2);
    expect(nextStan(999_998)).toBe(999_999);
    expect(nextStan(999_999)).toBe(1);
    expect(nextStan(0)).toBe(1);
  });

  it('makes 8-character terminal ids', () => {
    expect(newTerminalId(() => 0)).toBe('WEB00000');
    for (let i = 0; i < 50; i++) expect(newTerminalId()).toMatch(/^WEB[0-9A-Z]{5}$/);
  });

  it('builds exactly the fields of the switch DTO, in formats the switch accepts', () => {
    const builder = new TerminalRequestBuilder('WEB12345', 41, () => new Date('2026-10-18T09:15:30Z'));
    const r = builder.build(input);
    expect(Object.keys(r)).toEqual(DTO_FIELDS);
    expect(formatErrors(r)).toEqual([]);
    expect(r).toEqual({
      pan: '5555555555554444',
      expiry: '3012',
      amountMinor: 25000,
      currency: 356,
      mcc: '5411',
      merchantId: 'CRDEMO-GROC01',
      merchantName: 'Fresh Mart Pune',
      merchantCountry: '356',
      terminalId: 'WEB12345',
      acquirerId: '40001',
      stan: '000041',
      rrn: '629109000041',
      transmissionDateTime: '1018091530',
      posEntryMode: '051',
    });
  });

  it('accepts every demo card and merchant', () => {
    const builder = new TerminalRequestBuilder();
    for (const card of DEMO_CARDS) {
      for (const merchant of DEMO_MERCHANTS) {
        expect(formatErrors(builder.build({ card, merchant, amountMinor: 100 }))).toEqual([]);
      }
    }
  });

  it('gives every new request a unique STAN, RRN and transmission key, even within one second', () => {
    const at = new Date('2026-10-18T09:15:30Z');
    const builder = new TerminalRequestBuilder('WEB12345', 999_000, () => at);
    const requests = Array.from({ length: 2000 }, () => builder.build(input));
    const unique = (f: (r: AuthorizationRequestBody) => string) => new Set(requests.map(f)).size;
    expect(unique((r) => r.stan)).toBe(2000);
    expect(unique((r) => r.rrn)).toBe(2000);
    expect(unique((r) => [r.acquirerId, r.stan, r.transmissionDateTime, r.terminalId].join('|'))).toBe(2000);
    // Crossed the wrap: 999000 … 999999, then 000001 …
    expect(requests.map((r) => r.stan)).toContain('000001');
    expect(requests.every((r) => r.stan !== '000000')).toBe(true);
  });

  it('retransmits the identical payload', () => {
    let now = new Date('2026-10-18T09:15:30Z');
    const builder = new TerminalRequestBuilder('WEB12345', 7, () => now);
    const original = builder.build(input);
    now = new Date('2026-10-18T09:16:45Z');
    const again = builder.retransmission(original);
    expect(again).toEqual(original);
    expect(JSON.stringify(again)).toBe(JSON.stringify(original));
    // A retransmission does not consume a STAN: the next new request continues the sequence.
    expect(builder.build(input).stan).toBe('000008');
  });

  it('builds a conflicting duplicate with the same key and a different amount', () => {
    const builder = new TerminalRequestBuilder('WEB12345', 7, () => new Date('2026-10-18T09:15:30Z'));
    const original = builder.build(input);
    const conflicting = builder.conflictingDuplicate(original);
    expect(conflicting.amountMinor).toBe(original.amountMinor + 100);
    const key = (r: AuthorizationRequestBody) => [r.acquirerId, r.stan, r.transmissionDateTime, r.terminalId];
    expect(key(conflicting)).toEqual(key(original));
  });
});
