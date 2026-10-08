import {
  decryptSecret,
  deriveKey,
  encryptSecret,
  hashPassword,
  hmacSha256Hex,
  randomToken,
  sha256Hex,
  timingSafeEqualHex,
  verifyPassword,
} from './crypto';

describe('crypto utils', () => {
  it('randomToken returns distinct base64url strings of the requested entropy', () => {
    const a = randomToken(32);
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomToken(32)).not.toBe(a);
  });

  it('sha256Hex and hmacSha256Hex match known vectors', () => {
    expect(sha256Hex('abc')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    expect(hmacSha256Hex('key', 'The quick brown fox jumps over the lazy dog')).toBe(
      'f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8',
    );
  });

  it('timingSafeEqualHex compares hex strings and rejects mismatched or invalid input', () => {
    expect(timingSafeEqualHex('abcd', 'abcd')).toBe(true);
    expect(timingSafeEqualHex('abcd', 'abce')).toBe(false);
    expect(timingSafeEqualHex('abcd', 'abcdef')).toBe(false);
    expect(timingSafeEqualHex('', '')).toBe(false);
  });
});

describe('password hashing', () => {
  it('verifies the right password and rejects a wrong one', async () => {
    const hash = await hashPassword('correct horse');

    expect(hash).toMatch(/^scrypt\$16384\$8\$1\$[^$]+\$[^$]+$/);
    await expect(verifyPassword('correct horse', hash)).resolves.toBe(true);
    await expect(verifyPassword('correct horsE', hash)).resolves.toBe(false);
  });

  it('accepts a one-character password and keeps significant whitespace', async () => {
    const hash = await hashPassword('1');
    await expect(verifyPassword('1', hash)).resolves.toBe(true);
    await expect(verifyPassword('1 ', hash)).resolves.toBe(false);
  });

  it('salts: the same password hashes differently each time and never appears in the hash', async () => {
    const first = await hashPassword('hunter2');
    const second = await hashPassword('hunter2');

    expect(first).not.toBe(second);
    expect(first).not.toContain('hunter2');
  });

  it('rejects a malformed stored hash instead of throwing', async () => {
    await expect(verifyPassword('x', 'not-a-hash')).resolves.toBe(false);
    await expect(verifyPassword('x', '')).resolves.toBe(false);
  });
});

describe('secret encryption', () => {
  const key = deriveKey('unit-test-secret-unit-test-secret-1234', 'tokens');

  it('round-trips, never shows the plaintext, and differs each time', () => {
    const a = encryptSecret('{"refreshToken":"vbr_abc"}', key);
    const b = encryptSecret('{"refreshToken":"vbr_abc"}', key);

    expect(a).not.toContain('vbr_abc');
    expect(a).not.toBe(b);
    expect(decryptSecret(a, key)).toBe('{"refreshToken":"vbr_abc"}');
  });

  it('rejects a tampered value and a wrong key', () => {
    const value = encryptSecret('secret', key);
    const tampered = `${value.slice(0, -2)}${value.endsWith('A') ? 'B' : 'A'}A`;

    expect(() => decryptSecret(tampered, key)).toThrow();
    expect(() => decryptSecret(value, deriveKey('another-secret', 'tokens'))).toThrow();
    expect(() => decryptSecret('garbage', key)).toThrow();
  });

  it('derives different keys for different purposes', () => {
    expect(deriveKey('s', 'a').equals(deriveKey('s', 'b'))).toBe(false);
    expect(deriveKey('s', 'a')).toHaveLength(32);
  });
});
