import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  scrypt as scryptCallback,
  timingSafeEqual,
} from 'node:crypto';

export function randomToken(bytes: number): string {
  return randomBytes(bytes).toString('base64url');
}

export function sha256Hex(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

export function hmacSha256Hex(key: string, message: string): string {
  return createHmac('sha256', key).update(message).digest('hex');
}

/** Constant-time comparison of two hex strings; false on any length or format mismatch. */
export function timingSafeEqualHex(a: string, b: string): boolean {
  const left = Buffer.from(a, 'hex');
  const right = Buffer.from(b, 'hex');
  return left.length > 0 && left.length === right.length && timingSafeEqual(left, right);
}

// scrypt from Node's standard library: memory-hard, no native dependency to build.
// Cost parameters are stored in each hash so they can be raised later without a migration.
const SCRYPT = { N: 16384, r: 8, p: 1, keyLength: 32, saltBytes: 16 };

function scrypt(password: string, salt: Buffer, N: number, r: number, p: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scryptCallback(password, salt, SCRYPT.keyLength, { N, r, p }, (error, key) =>
      error ? reject(error) : resolve(key),
    );
  });
}

/** Returns `scrypt$N$r$p$<salt>$<hash>`. The password itself is never stored or logged. */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(SCRYPT.saltBytes);
  const key = await scrypt(password, salt, SCRYPT.N, SCRYPT.r, SCRYPT.p);
  return [
    'scrypt',
    SCRYPT.N,
    SCRYPT.r,
    SCRYPT.p,
    salt.toString('base64'),
    key.toString('base64'),
  ].join('$');
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, n, r, p, salt, hash] = stored.split('$');
  if (scheme !== 'scrypt' || !salt || !hash) {
    return false;
  }
  const expected = Buffer.from(hash, 'base64');
  const actual = await scrypt(
    password,
    Buffer.from(salt, 'base64'),
    Number(n),
    Number(r),
    Number(p),
  );
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

/** AES-256-GCM with a random nonce. Output is `v1.<nonce>.<tag>.<ciphertext>`, all base64url. */
export function encryptSecret(plaintext: string, key: Buffer): string {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  const data = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return ['v1', nonce, cipher.getAuthTag(), data]
    .map((part) => (typeof part === 'string' ? part : part.toString('base64url')))
    .join('.');
}

/** Throws if the value was tampered with or the key is wrong. */
export function decryptSecret(value: string, key: Buffer): string {
  const [version, nonce, tag, data] = value.split('.');
  if (version !== 'v1' || !nonce || !tag || !data) {
    throw new Error('unsupported ciphertext');
  }
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(nonce, 'base64url'));
  decipher.setAuthTag(Buffer.from(tag, 'base64url'));
  return Buffer.concat([
    decipher.update(Buffer.from(data, 'base64url')),
    decipher.final(),
  ]).toString('utf8');
}

/** A 32-byte key for one purpose, derived from a configured secret. */
export function deriveKey(secret: string, purpose: string): Buffer {
  return createHmac('sha256', secret).update(purpose).digest();
}
