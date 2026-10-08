import { ExecutionContext, createParamDecorator } from '@nestjs/common';

/** Who is calling, taken from the verified access token: never from the request body. */
export interface Principal {
  userId: string;
  deviceId: string;
  sessionId: string;
}

export interface RequestWithPrincipal {
  principal?: Principal;
}

export const CurrentPrincipal = createParamDecorator(
  (_data: unknown, context: ExecutionContext): Principal => {
    const principal = context.switchToHttp().getRequest<RequestWithPrincipal>().principal;
    if (!principal) {
      throw new Error('@CurrentPrincipal() used on a route without authentication');
    }
    return principal;
  },
);
