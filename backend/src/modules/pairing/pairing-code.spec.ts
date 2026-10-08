import { generateCode, normalizeCode } from './pairing.service';

describe('pairing codes', () => {
  it('generates four digits, leading zeros included', () => {
    const codes = Array.from({ length: 500 }, generateCode);
    expect(codes.every((code) => /^\d{4}$/.test(code))).toBe(true);
    expect(new Set(codes).size).toBeGreaterThan(400);
  });

  it.each([
    ['1234', '1234'],
    ['12 34', '1234'],
    [' 0042 ', '0042'],
    ['12-34', '1234'],
  ])('normalises "%s"', (input, expected) => {
    expect(normalizeCode(input)).toBe(expected);
  });

  it.each(['', '123', '12345', 'abcd', '12a4', '१२३४'])('rejects "%s"', (input) => {
    expect(normalizeCode(input)).toBeNull();
  });
});
