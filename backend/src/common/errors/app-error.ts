import { DEFAULT_MESSAGES, ERROR_CODES, ErrorCode } from './error-codes';

/** A deliberate, client-facing failure. `message` must be safe to show to users. */
export class AppError extends Error {
  readonly httpStatus: number;

  constructor(
    readonly code: ErrorCode,
    message: string = DEFAULT_MESSAGES[code],
    readonly details?: Record<string, unknown>,
    httpStatus: number = ERROR_CODES[code],
  ) {
    super(message);
    this.name = 'AppError';
    this.httpStatus = httpStatus;
  }
}

/** The JSON body of every non-2xx response (conventions §7). */
export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
    requestId?: string;
  };
}
