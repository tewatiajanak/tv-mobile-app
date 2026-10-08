import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { DevicesModule } from '../devices/devices.module';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import { SessionService } from './session.service';
import { TokenService } from './token.service';

@Module({
  imports: [DevicesModule],
  controllers: [AuthController],
  providers: [
    AuthService,
    SessionService,
    TokenService,
    { provide: APP_GUARD, useClass: JwtAuthGuard },
  ],
  exports: [SessionService, TokenService],
})
export class AuthModule {}
