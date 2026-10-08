/**
 * The single redaction list for backend logs (conventions §13).
 * `*.x` matches the key one level deep in any logged object, e.g. `{ body: { otp } }`.
 */
export const REDACT_PATHS: string[] = [
  'req.headers.authorization',
  'req.headers.cookie',
  'req.headers["x-pairing-poll-token"]',
  'tokensEnc',
  '*.tokensEnc',
  'res.headers["set-cookie"]',
  'password',
  'passwordHash',
  '*.passwordHash',
  'otp',
  'code',
  'token',
  'accessToken',
  'refreshToken',
  'pollToken',
  'secret',
  '*.password',
  '*.otp',
  '*.code',
  '*.token',
  '*.accessToken',
  '*.refreshToken',
  '*.pollToken',
  '*.secret',
];

export const REDACT_CENSOR = '[Redacted]';

/** Query strings often carry signed tokens, so only the path is ever logged. */
export function stripQuery(url: string | undefined): string | undefined {
  if (url === undefined) {
    return undefined;
  }
  const cut = url.search(/[?#]/);
  return cut === -1 ? url : url.slice(0, cut);
}
