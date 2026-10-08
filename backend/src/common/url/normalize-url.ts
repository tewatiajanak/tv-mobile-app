import { sha256Hex } from '../utils/crypto';

const MAX_URL_LENGTH = 2048;
const TRACKING_PARAMS = new Set(['fbclid', 'gclid', 'igshid', 'mc_cid', 'mc_eid', 'ref_src']);
const FILE_NAME = /\.[a-z0-9]{2,5}$/i;

export interface NormalizedUrl {
  /** Exactly what the user saved (trimmed): this is what players open. */
  sourceUrl: string;
  /** Without fragment and tracking parameters: what duplicates are judged on. */
  normalizedUrl: string;
  urlHash: string;
  /** Host without a leading "www.". */
  sourceDomain: string;
}

/**
 * Validates a user-supplied link and derives its comparison form. Returns null for anything
 * that is not a plain http(s) web link. Other query parameters are left untouched and in
 * order: signed video URLs break if they are rewritten.
 */
export function normalizeUrl(input: string): NormalizedUrl | null {
  const sourceUrl = input.trim();
  if (sourceUrl.length === 0 || sourceUrl.length > MAX_URL_LENGTH) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(sourceUrl);
  } catch {
    return null;
  }
  // Credentials in a URL would be stored and synced in the clear.
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) {
    return null;
  }
  if (
    !url.hostname.includes('.') &&
    url.hostname !== 'localhost' &&
    !/^[\d.:[\]a-f]+$/i.test(url.hostname)
  ) {
    return null;
  }
  url.hash = '';
  for (const name of [...url.searchParams.keys()]) {
    if (TRACKING_PARAMS.has(name.toLowerCase()) || name.toLowerCase().startsWith('utm_')) {
      url.searchParams.delete(name);
    }
  }
  const normalizedUrl = url.toString();
  return {
    sourceUrl,
    normalizedUrl,
    urlHash: sha256Hex(normalizedUrl),
    sourceDomain: url.hostname.replace(/^www\./, ''),
  };
}

/** `My.Holiday.2024.mp4` -> "My Holiday 2024"; otherwise "Video from example.com". */
export function defaultTitle(normalized: NormalizedUrl): string {
  let segment: string;
  try {
    segment = decodeURIComponent(new URL(normalized.normalizedUrl).pathname.split('/').pop() ?? '');
  } catch {
    segment = '';
  }
  if (FILE_NAME.test(segment)) {
    const name = segment
      .replace(FILE_NAME, '')
      .replace(/[._+-]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (name.length > 0) {
      return name.slice(0, 200);
    }
  }
  return `Video from ${normalized.sourceDomain}`;
}
