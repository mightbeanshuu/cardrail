import { displayMaskedPan, looksLikePan, luhnValid } from './pan';

describe('PAN guards', () => {
  it('validates Luhn', () => {
    expect(luhnValid('5555555555554444')).toBe(true);
    expect(luhnValid('5555555555554445')).toBe(false);
  });

  it('recognises full card numbers, with or without separators', () => {
    expect(looksLikePan('5555555555554444')).toBe(true);
    expect(looksLikePan('5555 5555 5555 4444')).toBe(true);
    expect(looksLikePan('MERCHANT000001')).toBe(false);
    expect(looksLikePan('4444')).toBe(false);
  });

  it('passes masked values through and masks anything unmasked', () => {
    expect(displayMaskedPan('555555******4444')).toBe('555555******4444');
    expect(displayMaskedPan('5555555555554444')).toBe('555555******4444');
    expect(displayMaskedPan('123456789012')).toBe('********9012');
    expect(displayMaskedPan(null)).toBe('—');
  });
});
