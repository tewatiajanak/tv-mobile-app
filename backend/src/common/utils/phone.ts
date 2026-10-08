import { CountryCode, parsePhoneNumberFromString } from 'libphonenumber-js';

/**
 * Normalises user input ("98765 43210", "+91 98765-43210") to E.164, or returns null when it
 * is not a possible number for the region. Numbers are identifiers here, not verified contacts.
 */
export function normalizePhone(input: string, defaultRegion: string): string | null {
  const parsed = parsePhoneNumberFromString(input.trim(), defaultRegion as CountryCode);
  return parsed?.isValid() ? parsed.number : null;
}

/** `+919876543210` -> `+91******3210`. The only form of a phone number that is logged or returned. */
export function maskPhone(e164: string): string {
  if (e164.length <= 7) {
    return e164.replace(/\d(?=\d{0,3}$)/g, '*');
  }
  return `${e164.slice(0, 3)}${'*'.repeat(e164.length - 7)}${e164.slice(-4)}`;
}
