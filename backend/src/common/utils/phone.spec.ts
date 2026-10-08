import { maskPhone, normalizePhone } from './phone';

describe('normalizePhone', () => {
  it.each([
    ['9876543210', '+919876543210'],
    ['98765 43210', '+919876543210'],
    ['+91 98765-43210', '+919876543210'],
    ['09876543210', '+919876543210'],
    ['  919876543210 ', '+919876543210'],
    ['+14155552671', '+14155552671'],
  ])('%s -> %s', (input, expected) => {
    expect(normalizePhone(input, 'IN')).toBe(expected);
  });

  it.each(['', '12345', 'abcdefghij', '+91 12345', '98765432101234567'])(
    'rejects "%s"',
    (input) => {
      expect(normalizePhone(input, 'IN')).toBeNull();
    },
  );
});

describe('maskPhone', () => {
  it('keeps the country prefix and the last four digits', () => {
    expect(maskPhone('+919876543210')).toBe('+91******3210');
    expect(maskPhone('+14155552671')).toBe('+14*****2671');
  });
});
