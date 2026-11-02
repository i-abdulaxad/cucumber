import { useSyncExternalStore } from 'react';
import { getSession, subscribe, type Session } from './session';

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, getSession, getSession);
}
