import {
  currencyExponent,
  describeAmountParseError,
  formatMinor,
  minorToDecimalString,
  parseMajorToMinor,
} from './money';

describe('minorToDecimalString', () => {
  it('places the decimal point by the currency exponent', () => {
    expect(minorToDecimalString(20010, 2)).toBe('200.10');
    expect(minorToDecimalString(5, 2)).toBe('0.05');
    expect(minorToDecimalString(0, 2)).toBe('0.00');
    expect(minorToDecimalString(-5, 2)).toBe('-0.05');
    expect(minorToDecimalString(1500, 0)).toBe('1500');
  });

  it('is exact for large amounts where float division would drift', () => {
    expect(minorToDecimalString(9007199254740991, 2)).toBe('90071992547409.91');
  });

  it('rejects non-integers', () => {
    expect(() => minorToDecimalString(1.5, 2)).toThrow(RangeError);
  });
});

describe('formatMinor', () => {
  it('formats INR (356) with the rupee sign and 2 decimals', () => {
    expect(formatMinor(25000, 356, 'en-IN')).toBe('₹250.00');
    expect(formatMinor(20010, 356, 'en-IN')).toBe('₹200.10');
  });

  it('uses Indian digit grouping in en-IN', () => {
    expect(formatMinor(20000000, 356, 'en-IN')).toBe('₹2,00,000.00');
  });

  it('formats USD (840) and EUR (978)', () => {
    expect(formatMinor(123456, 840, 'en-US')).toBe('$1,234.56');
    expect(formatMinor(123456, 978, 'en-IE')).toBe('€1,234.56');
    expect(formatMinor(5, 840, 'en-IN')).toBe('$0.05');
  });

  it('formats zero-exponent currencies without decimals', () => {
    expect(formatMinor(1500, 392, 'en-US')).toBe('¥1,500');
  });

  it('formats negative amounts', () => {
    expect(formatMinor(-25000, 356, 'en-IN')).toBe('-₹250.00');
  });

  it('falls back for unknown currencies and missing amounts', () => {
    expect(formatMinor(100, 999)).toBe('100 minor units (currency 999)');
    expect(formatMinor(null, 356)).toBe('—');
  });
});

describe('parseMajorToMinor', () => {
  it('converts "200.10" to 20010 without floating point', () => {
    expect(parseMajorToMinor('200.10', 2)).toEqual({ ok: true, minor: 20010 });
  });

  it('pads short fractions and accepts whole numbers', () => {
    expect(parseMajorToMinor('200.1', 2)).toEqual({ ok: true, minor: 20010 });
    expect(parseMajorToMinor('200', 2)).toEqual({ ok: true, minor: 20000 });
    expect(parseMajorToMinor(' 0.01 ', 2)).toEqual({ ok: true, minor: 1 });
  });

  it('is exact where float multiplication is not (0.29 * 100 = 28.999…)', () => {
    expect(parseMajorToMinor('0.29', 2)).toEqual({ ok: true, minor: 29 });
    expect(parseMajorToMinor('1.15', 2)).toEqual({ ok: true, minor: 115 });
    expect(parseMajorToMinor('4.35', 2)).toEqual({ ok: true, minor: 435 });
  });

  it('rejects more decimals than the currency has ("1.234")', () => {
    expect(parseMajorToMinor('1.234', 2)).toEqual({ ok: false, error: 'too-many-decimals' });
    expect(parseMajorToMinor('1.5', 0)).toEqual({ ok: false, error: 'too-many-decimals' });
  });

  it('rejects negative amounts', () => {
    expect(parseMajorToMinor('-1', 2)).toEqual({ ok: false, error: 'negative' });
    expect(parseMajorToMinor('-0.50', 2)).toEqual({ ok: false, error: 'negative' });
  });

  it('rejects empty and malformed input', () => {
    expect(parseMajorToMinor('', 2)).toEqual({ ok: false, error: 'empty' });
    expect(parseMajorToMinor('   ', 2)).toEqual({ ok: false, error: 'empty' });
    expect(parseMajorToMinor(null, 2)).toEqual({ ok: false, error: 'empty' });
    for (const bad of ['1,000.00', '1e3', '+5', '.5', '5.', 'abc', '1.2.3', '0x10', '१००']) {
      expect(parseMajorToMinor(bad, 2)).toEqual({ ok: false, error: 'invalid' });
    }
  });

  it('rejects amounts beyond the safe integer range', () => {
    expect(parseMajorToMinor('90071992547409.92', 2)).toEqual({ ok: false, error: 'too-large' });
    expect(parseMajorToMinor('90071992547409.91', 2)).toEqual({ ok: true, minor: 9007199254740991 });
  });

  it('round-trips with minorToDecimalString', () => {
    for (const minor of [0, 1, 99, 100, 20010, 123456789]) {
      const parsed = parseMajorToMinor(minorToDecimalString(minor, 2), 2);
      expect(parsed).toEqual({ ok: true, minor });
    }
  });
});

describe('currencyExponent and error messages', () => {
  it('knows exponents and defaults to 2', () => {
    expect(currencyExponent(356)).toBe(2);
    expect(currencyExponent(392)).toBe(0);
    expect(currencyExponent(999)).toBe(2);
  });

  it('describes each parse error', () => {
    expect(describeAmountParseError('too-many-decimals', 2)).toBe('Use at most 2 decimal places.');
    expect(describeAmountParseError('negative', 2)).toBe('Amount cannot be negative.');
    expect(describeAmountParseError('too-many-decimals', 0)).toContain('whole number');
  });
});
