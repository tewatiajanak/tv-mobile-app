import type { Request } from 'express';
import { sha256Hex } from './crypto';

/** Raw IPs are never stored or logged; rate limits and security logs use this salted hash. */
export function clientIpHash(req: Request, salt: string): string {
  // req.ip honours X-Forwarded-For only when TRUST_PROXY is on.
  return sha256Hex(`${req.ip ?? 'unknown'}${salt}`).slice(0, 32);
}
