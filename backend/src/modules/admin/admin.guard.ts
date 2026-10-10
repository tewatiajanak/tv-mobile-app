import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import type { Request } from 'express';
import { AppError } from '../../common/errors/app-error';
import { AdminService } from './admin.service';

/** Admin routes are @Public() for the app's guard and checked here against an admin token. */
@Injectable()
export class AdminGuard implements CanActivate {
  constructor(private readonly admin: AdminService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest<Request>();
    const [scheme, token] = (req.headers.authorization ?? '').split(' ');
    this.admin.requireEnabled();
    if (scheme?.toLowerCase() !== 'bearer' || !token) {
      throw new AppError('UNAUTHENTICATED');
    }
    await this.admin.verify(token);
    return true;
  }
}
