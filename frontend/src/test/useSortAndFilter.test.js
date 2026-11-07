import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useSortAndFilter from '../hooks/useSortAndFilter.js';

const accessors = {
  name: (r) => r.name,
  count: (r) => r.count,
  on: (r) => r.on,
  tags: (r) => r.tags,
};

const rows = [
  { name: 'banana', count: 2, on: false, tags: 'Coast Dawn' },
  { name: 'Apple', count: 10, on: true, tags: 'Hill' },
  { name: 'cherry', count: 1, on: true, tags: null },
];
const names = (list) => list.map((r) => r.name);

function hook(key = 'name', dir = 'asc') {
  return renderHook(() => useSortAndFilter(key, dir, accessors));
}

describe('useSortAndFilter', () => {
  it('starts on the default key and direction', () => {
    const { result } = hook('count', 'desc');
    expect(result.current.sortKey).toBe('count');
    expect(result.current.sortDir).toBe('desc');
  });

  it('sorts strings by locale ignoring case', () => {
    const { result } = hook();
    expect(names(result.current.apply(rows))).toEqual(['Apple', 'banana', 'cherry']);
  });

  it('sorts numbers numerically, not lexically', () => {
    const { result } = hook('count');
    expect(result.current.apply(rows).map((r) => r.count)).toEqual([1, 2, 10]);
  });

  it('sorts booleans with true first ascending, false first descending', () => {
    const { result } = hook('on');
    expect(result.current.apply(rows)[2].name).toBe('banana');
    act(() => result.current.setSort('on', 'desc'));
    expect(result.current.apply(rows)[0].name).toBe('banana');
  });

  it('does not mutate the input array', () => {
    const { result } = hook('count');
    const input = [...rows];
    result.current.apply(input);
    expect(input).toEqual(rows);
  });

  it('handleSort on the active key toggles direction both ways', () => {
    const { result } = hook();
    act(() => result.current.handleSort('name'));
    expect(result.current.sortDir).toBe('desc');
    expect(names(result.current.apply(rows))).toEqual(['cherry', 'banana', 'Apple']);
    act(() => result.current.handleSort('name'));
    expect(result.current.sortDir).toBe('asc');
  });

  it('handleSort on a new key switches to it ascending', () => {
    const { result } = hook('name', 'desc');
    act(() => result.current.handleSort('count'));
    expect(result.current.sortKey).toBe('count');
    expect(result.current.sortDir).toBe('asc');
  });

  it('setSort sets column and direction at once', () => {
    const { result } = hook();
    act(() => result.current.setSort('count', 'desc'));
    expect(result.current.sortKey).toBe('count');
    expect(result.current.sortDir).toBe('desc');
    expect(result.current.apply(rows).map((r) => r.count)).toEqual([10, 2, 1]);
  });

  it('keeps nulls last in both directions', () => {
    const data = [{ name: 'a', count: null }, { name: 'b', count: 5 }, { name: 'c', count: null }, { name: 'd', count: 3 }];
    const { result } = hook('count');
    expect(names(result.current.apply(data))).toEqual(['d', 'b', 'a', 'c']);
    act(() => result.current.setSort('count', 'desc'));
    expect(names(result.current.apply(data))).toEqual(['b', 'd', 'a', 'c']);
  });

  it('string filters substring-match case-insensitively', () => {
    const { result } = hook();
    act(() => result.current.setFilter('name', 'AN'));
    expect(names(result.current.apply(rows))).toEqual(['banana']);
    expect(result.current.getFilterValue('name')).toBe('AN');
  });

  it('a string filter drops rows whose value is null', () => {
    const { result } = hook();
    act(() => result.current.setFilter('tags', 'hill'));
    expect(names(result.current.apply(rows))).toEqual(['Apple']);
  });

  it('array filters AND their values together and drop null values', () => {
    const { result } = hook();
    act(() => result.current.setFilter('tags', ['coast', 'DAWN']));
    expect(names(result.current.apply(rows))).toEqual(['banana']);
    act(() => result.current.setFilter('tags', ['coast', 'hill']));
    expect(result.current.apply(rows)).toEqual([]);
    act(() => result.current.setFilter('tags', ['a']));
    expect(names(result.current.apply(rows))).not.toContain('cherry');
  });

  it('an empty string or empty array filter is ignored', () => {
    const { result } = hook();
    act(() => result.current.setFilter('name', ''));
    act(() => result.current.setFilter('tags', []));
    expect(result.current.apply(rows)).toHaveLength(3);
  });

  it('a filter on a key with no accessor is ignored', () => {
    const { result } = hook();
    act(() => result.current.setFilter('unknown', 'zzz'));
    expect(result.current.apply(rows)).toHaveLength(3);
  });

  it('filters combine across keys', () => {
    const { result } = hook();
    act(() => result.current.setFilter('name', 'a'));
    act(() => result.current.setFilter('tags', 'hill'));
    expect(names(result.current.apply(rows))).toEqual(['Apple']);
  });

  it('getFilterValue is an empty string for an unset filter', () => {
    const { result } = hook();
    expect(result.current.getFilterValue('name')).toBe('');
  });

  it('leaves order alone when the sort key has no accessor', () => {
    const { result } = hook('missing');
    expect(names(result.current.apply(rows))).toEqual(['banana', 'Apple', 'cherry']);
  });
});
