import { v7 as uuidv7 } from 'uuid';

/** UUIDv7: time-ordered, so ids sort by creation time and index well. */
export function newId(): string {
  return uuidv7();
}
