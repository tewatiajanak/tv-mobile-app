import { User } from '@prisma/client';
import { maskPhone } from '../../common/utils/phone';

export interface UserView {
  id: string;
  phoneMasked: string;
  displayName: string;
  createdAt: string;
}

/** The only shape in which a user leaves the API: no hash, no full phone number. */
export function toUserView(user: User): UserView {
  return {
    id: user.id,
    phoneMasked: maskPhone(user.phoneE164),
    displayName: user.displayName,
    createdAt: user.createdAt.toISOString(),
  };
}
