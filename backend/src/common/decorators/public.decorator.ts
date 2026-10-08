import { SetMetadata } from '@nestjs/common';

export const IS_PUBLIC = 'isPublic';

/** Opts a route out of the global JwtAuthGuard. Everything else requires a valid access token. */
export const Public = () => SetMetadata(IS_PUBLIC, true);
