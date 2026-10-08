import { Body, Controller, Get, Patch } from '@nestjs/common';
import { ApiBearerAuth, ApiOkResponse, ApiOperation, ApiProperty, ApiTags } from '@nestjs/swagger';
import { Transform } from 'class-transformer';
import { IsNotEmpty, IsString, MaxLength } from 'class-validator';
import { CurrentPrincipal, Principal } from '../../common/decorators/current-principal.decorator';
import { AppError } from '../../common/errors/app-error';
import { PrismaService } from '../../infra/prisma/prisma.service';
import { UserDto } from '../auth/dto/auth.dto';
import { toUserView } from './user.mapper';

class UpdateMeDto {
  @ApiProperty({ example: 'Janak' })
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsString()
  @IsNotEmpty({ message: 'Enter your name' })
  @MaxLength(60, { message: 'Name is too long' })
  displayName!: string;
}

@ApiTags('users')
@ApiBearerAuth()
@Controller('users')
export class UsersController {
  constructor(private readonly prisma: PrismaService) {}

  @Get('me')
  @ApiOperation({ summary: 'The signed-in account.' })
  @ApiOkResponse({ type: UserDto })
  async me(@CurrentPrincipal() principal: Principal): Promise<UserDto> {
    const user = await this.prisma.user.findUnique({ where: { id: principal.userId } });
    if (!user) {
      throw new AppError('NOT_FOUND');
    }
    return toUserView(user);
  }

  @Patch('me')
  @ApiOperation({ summary: 'Change the display name.' })
  @ApiOkResponse({ type: UserDto })
  async update(
    @CurrentPrincipal() principal: Principal,
    @Body() body: UpdateMeDto,
  ): Promise<UserDto> {
    const user = await this.prisma.user.update({
      where: { id: principal.userId },
      data: { displayName: body.displayName },
    });
    return toUserView(user);
  }
}
