import { Module } from '@nestjs/common';
import { AdminController, AdminPageController } from './admin.controller';
import { AdminGuard } from './admin.guard';
import { AdminMailer } from './admin.mailer';
import { AdminService } from './admin.service';

@Module({
  controllers: [AdminController, AdminPageController],
  providers: [AdminService, AdminGuard, AdminMailer],
})
export class AdminModule {}
