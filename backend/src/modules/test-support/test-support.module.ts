import { Body, Controller, Get, Module, Post } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { Public } from '../../common/decorators/public.decorator';
import { IsInt, IsString, Length, Max, Min } from 'class-validator';

class EchoDto {
  @IsString()
  @Length(1, 10)
  name!: string;

  @IsInt()
  @Min(1)
  @Max(5)
  count!: number;
}

/**
 * Routes that exist only so e2e tests can exercise the validation pipe and the exception
 * filter over real HTTP. AppModule registers this module only when NODE_ENV=test.
 */
@ApiExcludeController()
@Public()
@Controller('_test')
class TestSupportController {
  @Post('echo')
  echo(@Body() body: EchoDto): EchoDto {
    return body;
  }

  @Get('boom')
  boom(): never {
    throw new Error('secret internal detail: SELECT * FROM users');
  }
}

@Module({ controllers: [TestSupportController] })
export class TestSupportModule {}
