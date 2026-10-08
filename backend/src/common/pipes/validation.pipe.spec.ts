import { ArgumentMetadata } from '@nestjs/common';
import { Type } from 'class-transformer';
import { IsInt, IsString, Length, ValidateNested } from 'class-validator';
import { AppError } from '../errors/app-error';
import { createValidationPipe } from './validation.pipe';

class DeviceDto {
  @IsString()
  @Length(1, 5)
  name!: string;
}

class SampleDto {
  @IsInt()
  count!: number;

  @ValidateNested()
  @Type(() => DeviceDto)
  device!: DeviceDto;
}

const metadata: ArgumentMetadata = { type: 'body', metatype: SampleDto };

async function failure(value: unknown): Promise<AppError> {
  try {
    await createValidationPipe().transform(value, metadata);
  } catch (error) {
    return error as AppError;
  }
  throw new Error('expected validation to fail');
}

describe('createValidationPipe', () => {
  it('passes and transforms a valid body', async () => {
    const result: unknown = await createValidationPipe().transform(
      { count: 2, device: { name: 'TV' } },
      metadata,
    );

    expect(result).toBeInstanceOf(SampleDto);
    expect((result as SampleDto).device).toBeInstanceOf(DeviceDto);
  });

  it('reports failures as VALIDATION_FAILED with per-field details, including nested paths', async () => {
    const error = await failure({ count: 'two', device: { name: 'far too long' } });

    expect(error).toBeInstanceOf(AppError);
    expect(error.code).toBe('VALIDATION_FAILED');
    expect(error.httpStatus).toBe(400);
    expect(error.details).toEqual({
      fields: {
        count: ['count must be an integer number'],
        'device.name': ['name must be shorter than or equal to 5 characters'],
      },
    });
  });

  it('rejects properties that are not in the DTO (mass-assignment guard)', async () => {
    const error = await failure({ count: 1, device: { name: 'TV' }, userId: 'someone-else' });

    expect(error.details).toEqual({ fields: { userId: ['property userId should not exist'] } });
  });

  it('does not echo submitted values', async () => {
    const error = await failure({ count: 'super-secret-value', device: { name: 'TV' } });

    expect(JSON.stringify(error.details)).not.toContain('super-secret-value');
  });
});
