/**
 * The single registry of API error codes. Clients switch on `code`, so a code is never renamed
 * or reused; each phase appends its own.
 */
export const ERROR_CODES = {
  VALIDATION_FAILED: 400,
  BAD_REQUEST: 400,
  UNAUTHENTICATED: 401,
  TOKEN_EXPIRED: 401,
  FORBIDDEN: 403,
  ENTITLEMENT_LIMIT: 403,
  NOT_FOUND: 404,
  METHOD_NOT_ALLOWED: 405,
  VERSION_CONFLICT: 409,
  DUPLICATE: 409,
  CONFLICT: 409,
  PAYLOAD_TOO_LARGE: 413,
  UNSUPPORTED_MEDIA_TYPE: 415,
  RATE_LIMITED: 429,
  INTERNAL: 500,
  NOT_READY: 503,
  SERVICE_UNAVAILABLE: 503,
  // Phase 2 — authentication
  INVALID_CREDENTIALS: 401,
  PHONE_ALREADY_REGISTERED: 409,
  REFRESH_INVALID: 401,
  SESSION_REVOKED: 401,
  REFRESH_RACE: 409,
  USER_SUSPENDED: 403,
  // Phase 3 — devices and pairing
  PAIRING_CODE_INVALID: 404,
  PAIRING_ALREADY_CLAIMED: 409,
  PAIRING_EXPIRED: 410,
  // Phase 4 — saved videos
  URL_NOT_ALLOWED: 400,
  DUPLICATE_VIDEO: 409,
} as const;

export type ErrorCode = keyof typeof ERROR_CODES;

/** User-safe default messages, used when the thrower gives none. */
export const DEFAULT_MESSAGES: Record<ErrorCode, string> = {
  VALIDATION_FAILED: 'The request is invalid.',
  BAD_REQUEST: 'The request could not be understood.',
  UNAUTHENTICATED: 'Authentication is required.',
  TOKEN_EXPIRED: 'Your session has expired. Please sign in again.',
  FORBIDDEN: 'You are not allowed to do that.',
  ENTITLEMENT_LIMIT: 'Your plan limit has been reached.',
  NOT_FOUND: 'Not found.',
  METHOD_NOT_ALLOWED: 'Method not allowed.',
  VERSION_CONFLICT: 'This item was changed elsewhere. Reload and try again.',
  DUPLICATE: 'This already exists.',
  CONFLICT: 'The request conflicts with the current state.',
  PAYLOAD_TOO_LARGE: 'The request is too large.',
  UNSUPPORTED_MEDIA_TYPE: 'Unsupported content type.',
  RATE_LIMITED: 'Too many requests. Please try again later.',
  INTERNAL: 'Something went wrong. Please try again.',
  NOT_READY: 'The service is not ready.',
  SERVICE_UNAVAILABLE: 'The service is temporarily unavailable.',
  INVALID_CREDENTIALS: 'Wrong mobile number or password.',
  PHONE_ALREADY_REGISTERED: 'This mobile number already has an account. Sign in instead.',
  REFRESH_INVALID: 'Please sign in again.',
  SESSION_REVOKED: 'You were signed out. Please sign in again.',
  REFRESH_RACE: 'Please try again.',
  USER_SUSPENDED: 'This account is suspended.',
  PAIRING_CODE_INVALID: 'That code is not valid. Check the code on your TV and try again.',
  PAIRING_ALREADY_CLAIMED: 'This TV is already being connected from another phone.',
  PAIRING_EXPIRED: 'That code has expired. Use the new code on your TV.',
  URL_NOT_ALLOWED: 'That is not a link Dekho can save. Use a web link starting with http or https.',
  DUPLICATE_VIDEO: 'This link is already in your library.',
};
