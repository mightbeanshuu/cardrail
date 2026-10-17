import { luhnValid } from '../shared/pan';
import { DEMO_CARDS, maskedPan, panSuffix } from './demo-cards';

/**
 * Mastercard test numbers published by Mastercard and by payment providers' test documentation.
 * A number not on this list must not be added to the demo without checking it is a test number.
 */
const PUBLISHED_TEST_PANS = new Set([
  '5555555555554444',
  '5105105105105100',
  '2223003122003222',
  '5200828282828210',
  '5425233430109903',
  '2223000048410010',
]);

function inMastercardRange(pan: string): boolean {
  const two = Number(pan.slice(0, 2));
  const four = Number(pan.slice(0, 4));
  return (two >= 51 && two <= 55) || (four >= 2221 && four <= 2720);
}

/** The switch's V6 stand_in_limit seed for INR: longest matching BIN prefix wins. */
function seededStandInLimit(pan: string): number {
  if (pan.startsWith('555555')) return 500_000;
  if (pan.startsWith('5')) return 250_000;
  return 100_000;
}

describe('demo cards', () => {
  it('contains only Luhn-valid, 16-digit, published Mastercard test numbers', () => {
    for (const card of DEMO_CARDS) {
      expect(card.pan, card.id).toMatch(/^\d{16}$/);
      expect(luhnValid(card.pan), card.id).toBe(true);
      expect(inMastercardRange(card.pan), card.id).toBe(true);
      expect(PUBLISHED_TEST_PANS.has(card.pan), card.id).toBe(true);
    }
  });

  it('has unique ids and numbers', () => {
    expect(new Set(DEMO_CARDS.map((c) => c.id)).size).toBe(DEMO_CARDS.length);
    expect(new Set(DEMO_CARDS.map((c) => c.pan)).size).toBe(DEMO_CARDS.length);
  });

  it('covers an approval, insufficient funds, a blocked account and an unknown card', () => {
    expect(DEMO_CARDS.map((c) => c.expectedCode)).toEqual(expect.arrayContaining(['00', '51', '05', '14']));
    expect(DEMO_CARDS[0].expectedCode).toBe('00');
  });

  it("states each card's stand-in limit as the switch seeds it", () => {
    for (const card of DEMO_CARDS) {
      expect(card.standInLimitMinor, card.id).toBe(seededStandInLimit(card.pan));
    }
  });

  it('masks to first 6 and last 4, the same form the switch returns', () => {
    expect(maskedPan({ pan: '5555555555554444' })).toBe('555555******4444');
    expect(panSuffix({ pan: '5555555555554444' })).toBe('··4444');
    for (const card of DEMO_CARDS) {
      const masked = maskedPan(card);
      expect(masked).toBe(card.pan.slice(0, 6) + '******' + card.pan.slice(-4));
      expect(masked).not.toBe(card.pan);
    }
  });

  it('never puts a full number in the text a visitor reads', () => {
    for (const card of DEMO_CARDS) {
      expect(`${card.label} ${card.hint}`).not.toMatch(/\d{12,}/);
    }
  });
});
