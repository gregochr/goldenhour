import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useRatingLens from '../hooks/useRatingLens.js';
import { PLAN_RATING_KEY } from '../utils/ratingLens.js';

describe('useRatingLens — ids the bar does not offer', () => {
  beforeEach(() => localStorage.clear());

  it('ignores the drill-down-only 5-star floor: nothing selected, nothing stored', () => {
    const { result } = renderHook(() => useRatingLens());

    act(() => result.current.selectFloor('5'));

    expect(result.current.floorId).toBe('any');
    expect(result.current.minRating).toBeNull();
    expect(localStorage.getItem(PLAN_RATING_KEY)).toBeNull();
  });

  it('ignores an unknown id and keeps the floor already chosen', () => {
    const { result } = renderHook(() => useRatingLens());
    act(() => result.current.selectFloor('4'));

    act(() => result.current.selectFloor('bogus'));

    expect(result.current.floorId).toBe('4');
    expect(result.current.minRating).toBe(4);
    expect(JSON.parse(localStorage.getItem(PLAN_RATING_KEY))).toEqual({ rating: '4' });
  });
});
