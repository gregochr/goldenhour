import { useState } from 'react';

/**
 * Like useState, but backed by localStorage. Reads initial value from storage
 * on mount (falls back to defaultValue on missing key or parse error). Writes
 * on every change.
 *
 * @param {string} key - localStorage key
 * @param {*} defaultValue - value to use when nothing is stored
 * @returns {[*, function]} [value, setValue]
 */
export default function useLocalStorageState(key, defaultValue) {
  const [value, setInternalValue] = useState(() => {
    try {
      const stored = localStorage.getItem(key);
      if (stored === null) return defaultValue;
      return JSON.parse(stored);
    } catch {
      return defaultValue;
    }
  });

  const setValue = (newValue) => {
    setInternalValue(newValue);
    try {
      localStorage.setItem(key, JSON.stringify(newValue));
    } catch {
      // Quota exceeded or private-browsing restriction — ignore silently
    }
  };

  return [value, setValue];
}
