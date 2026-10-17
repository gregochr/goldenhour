import { useSyncExternalStore } from 'react';
import { getRewind, subscribeRewind } from '../utils/rewind.js';

/**
 * The current rewind ({@code utils/rewind.js}) as React state: re-renders the caller when an admin
 * sets or clears one.
 *
 * @returns {{ to: string, focus: { date: string, eventType: string } | null } | null}
 */
export function useRewind() {
  return useSyncExternalStore(subscribeRewind, getRewind, getRewind);
}
