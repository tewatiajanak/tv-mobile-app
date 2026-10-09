import { Module } from '@nestjs/common';
import { AdminController, AdminPageController } from './admin.controller';
import { AdminGuard } from './admin.guard';
import { AdminService } from './admin.service';

@Module({
  controllers: [AdminController, AdminPageController],
  providers: [AdminService, AdminGuard],
})
export class AdminModule {}
