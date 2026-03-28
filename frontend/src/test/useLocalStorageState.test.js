import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useLocalStorageState from '../hooks/useLocalStorageState.js';

describe('useLocalStorageState', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns defaultValue when no item is stored', () => {
    const { result } = renderHook(() => useLocalStorageState('test-key', 42));
    expect(result.current[0]).toBe(42);
  });

  it('reads existing value from localStorage on mount', () => {
    localStorage.setItem('test-key', JSON.stringify(99));
    const { result } = renderHook(() => useLocalStorageState('test-key', 0));
    expect(result.current[0]).toBe(99);
  });

  it('writes value to localStorage when setValue is called', () => {
    const { result } = renderHook(() => useLocalStorageState('test-key', 0));
    act(() => {
      result.current[1](7);
    });
    expect(localStorage.getItem('test-key')).toBe('7');
    expect(result.current[0]).toBe(7);
  });

  it('updates state when setValue is called', () => {
    const { result } = renderHook(() => useLocalStorageState('my-key', 'initial'));
    act(() => {
      result.current[1]('updated');
    });
    expect(result.current[0]).toBe('updated');
  });

  it('falls back to defaultValue when stored JSON is invalid', () => {
    localStorage.setItem('bad-key', 'not-valid-json{{{');
    const { result } = renderHook(() => useLocalStorageState('bad-key', 'fallback'));
    expect(result.current[0]).toBe('fallback');
  });

  it('works with object values', () => {
    const { result } = renderHook(() => useLocalStorageState('obj-key', {}));
    act(() => {
      result.current[1]({ foo: 'bar' });
    });
    expect(result.current[0]).toEqual({ foo: 'bar' });
    expect(JSON.parse(localStorage.getItem('obj-key'))).toEqual({ foo: 'bar' });
  });

  it('works with boolean values', () => {
    const { result } = renderHook(() => useLocalStorageState('bool-key', false));
    act(() => {
      result.current[1](true);
    });
    expect(result.current[0]).toBe(true);
  });
});
