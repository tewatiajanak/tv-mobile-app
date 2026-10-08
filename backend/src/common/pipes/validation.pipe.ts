import { ValidationError, ValidationPipe } from '@nestjs/common';
import { AppError } from '../errors/app-error';

/** Flattens class-validator errors to `{ "device.name": ["name must be a string"] }`. */
export function collectFieldErrors(
  errors: ValidationError[],
  parentPath = '',
  fields: Record<string, string[]> = {},
): Record<string, string[]> {
  for (const error of errors) {
    const path = parentPath ? `${parentPath}.${error.property}` : error.property;
    if (error.constraints) {
      fields[path] = Object.values(error.constraints);
    }
    if (error.children?.length) {
      collectFieldErrors(error.children, path, fields);
    }
  }
  return fields;
}

/** The global pipe: unknown properties are rejected, failures use the error envelope. */
export function createValidationPipe(): ValidationPipe {
  return new ValidationPipe({
    whitelist: true,
    forbidNonWhitelisted: true,
    transform: true,
    // Never echo submitted values back in validation errors.
    validationError: { target: false, value: false },
    exceptionFactory: (errors) =>
      new AppError('VALIDATION_FAILED', undefined, { fields: collectFieldErrors(errors) }),
  });
}
