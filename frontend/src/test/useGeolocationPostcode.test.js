import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useGeolocationPostcode, {
  isGeolocationAvailable, LOCATION_DENIED, LOCATION_NOT_UK, LOCATION_FAILED,
} from '../hooks/useGeolocationPostcode.js';

vi.mock('../api/settingsApi.js', () => ({ reverseLookupPostcode: vi.fn() }));

import { reverseLookupPostcode } from '../api/settingsApi.js';

const DURHAM = { postcode: 'DH1 3SS', placeName: 'Durham', latitude: 54.776, longitude: -1.575 };

/** Installs a fake `navigator.geolocation` whose getCurrentPosition is the given function. */
function installGeolocation(getCurrentPosition) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true, value: { getCurrentPosition },
  });
}

/** A browser that answers with a fix. */
const fix = (latitude, longitude) => vi.fn((ok) => ok({ coords: { latitude, longitude } }));
/** A browser that fails with a GeolocationPositionError-shaped object — jsdom has no such class. */
const failWith = (code) => vi.fn((_ok, fail) => fail({ code }));

describe('useGeolocationPostcode', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    delete navigator.geolocation;
  });

  it('asks the browser with a coarse, cached, 10 s request and the server with the fix', async () => {
    const getCurrentPosition = fix(54.776, -1.575);
    installGeolocation(getCurrentPosition);
    reverseLookupPostcode.mockResolvedValue(DURHAM);
    const { result } = renderHook(() => useGeolocationPostcode());

    let found;
    await act(async () => { found = await result.current.locate(); });

    expect(getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(getCurrentPosition.mock.calls[0][2]).toEqual({
      enableHighAccuracy: false, timeout: 10000, maximumAge: 600000,
    });
    expect(reverseLookupPostcode).toHaveBeenCalledTimes(1);
    expect(reverseLookupPostcode).toHaveBeenCalledWith(54.776, -1.575);
    expect(found).toEqual(DURHAM);
    expect(result.current.error).toBeNull();
    expect(result.current.locating).toBe(false);
  });

  it('is busy from the press until the lookup lands', async () => {
    let deliver;
    installGeolocation(vi.fn((ok) => { deliver = ok; }));
    let finishLookup;
    reverseLookupPostcode.mockReturnValue(new Promise((resolve) => { finishLookup = resolve; }));
    const { result } = renderHook(() => useGeolocationPostcode());

    let pending;
    act(() => { pending = result.current.locate(); });
    expect(result.current.locating).toBe(true);

    await act(async () => { deliver({ coords: { latitude: 1, longitude: 2 } }); });
    expect(result.current.locating).toBe(true); // the browser has answered; the server has not

    await act(async () => { finishLookup(DURHAM); await pending; });
    expect(result.current.locating).toBe(false);
  });

  it.each([
    [1, LOCATION_DENIED, 'Location access is off. Enter your postcode instead.'],
    [2, LOCATION_FAILED, "Couldn't get your location. Enter your postcode instead."],
    [3, LOCATION_FAILED, "Couldn't get your location. Enter your postcode instead."],
  ])('browser error code %i reads "%s"', async (code, constant, literal) => {
    installGeolocation(failWith(code));
    const { result } = renderHook(() => useGeolocationPostcode());

    let found;
    await act(async () => { found = await result.current.locate(); });

    expect(found).toBeNull();
    expect(result.current.error).toBe(literal);
    expect(result.current.error).toBe(constant);
    expect(reverseLookupPostcode).not.toHaveBeenCalled();
    expect(result.current.locating).toBe(false);
  });

  it('an error with no code at all reads as the generic sentence', async () => {
    installGeolocation(vi.fn((_ok, fail) => fail(undefined)));
    const { result } = renderHook(() => useGeolocationPostcode());

    await act(async () => { await result.current.locate(); });

    expect(result.current.error).toBe(LOCATION_FAILED);
  });

  it('a 400 from the reverse endpoint reads "no UK postcode"', async () => {
    installGeolocation(fix(48.85, 2.35));
    reverseLookupPostcode.mockRejectedValue({ response: { status: 400, data: { error: 'x' } } });
    const { result } = renderHook(() => useGeolocationPostcode());

    let found;
    await act(async () => { found = await result.current.locate(); });

    expect(found).toBeNull();
    expect(result.current.error).toBe("We couldn't find a UK postcode for your location.");
    expect(result.current.error).toBe(LOCATION_NOT_UK);
  });

  it.each([
    ['a 502', { response: { status: 502 } }],
    ['a 500', { response: { status: 500 } }],
    ['a network failure with no response', new Error('Network Error')],
  ])('%s reads as the generic sentence', async (_name, failure) => {
    installGeolocation(fix(54.776, -1.575));
    reverseLookupPostcode.mockRejectedValue(failure);
    const { result } = renderHook(() => useGeolocationPostcode());

    await act(async () => { await result.current.locate(); });

    expect(result.current.error).toBe("Couldn't get your location. Enter your postcode instead.");
  });

  it('a browser that throws synchronously reads as the generic sentence', async () => {
    installGeolocation(vi.fn(() => { throw new Error('SecurityError'); }));
    const { result } = renderHook(() => useGeolocationPostcode());

    let found;
    await act(async () => { found = await result.current.locate(); });

    expect(found).toBeNull();
    expect(result.current.error).toBe(LOCATION_FAILED);
    expect(result.current.locating).toBe(false);
  });

  it('a new attempt clears the last error, and clearError does too', async () => {
    installGeolocation(failWith(1));
    const { result } = renderHook(() => useGeolocationPostcode());
    await act(async () => { await result.current.locate(); });
    expect(result.current.error).toBe(LOCATION_DENIED);

    act(() => { result.current.clearError(); });
    expect(result.current.error).toBeNull();

    await act(async () => { await result.current.locate(); });
    installGeolocation(fix(54.776, -1.575));
    reverseLookupPostcode.mockResolvedValue(DURHAM);
    await act(async () => { await result.current.locate(); });
    expect(result.current.error).toBeNull();
  });

  it('gives up on a browser that answers neither callback, so Locating… cannot stay up for ever', async () => {
    vi.useFakeTimers();
    try {
      installGeolocation(vi.fn()); // Firefox, permission prompt dismissed: no callback, ever
      const { result } = renderHook(() => useGeolocationPostcode());
      let pending;
      act(() => { pending = result.current.locate(); });
      expect(result.current.locating).toBe(true);

      await act(async () => { vi.advanceTimersByTime(14999); });
      expect(result.current.locating).toBe(true);

      await act(async () => { vi.advanceTimersByTime(2); await pending; });
      expect(result.current.locating).toBe(false);
      expect(result.current.error).toBe(LOCATION_FAILED);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a late answer after giving up changes nothing', async () => {
    vi.useFakeTimers();
    try {
      let deliver;
      installGeolocation(vi.fn((ok) => { deliver = ok; }));
      reverseLookupPostcode.mockResolvedValue(DURHAM);
      const { result } = renderHook(() => useGeolocationPostcode());
      let pending;
      act(() => { pending = result.current.locate(); });
      await act(async () => { vi.advanceTimersByTime(15001); await pending; });

      await act(async () => { deliver({ coords: { latitude: 1, longitude: 2 } }); });

      expect(result.current.error).toBe(LOCATION_FAILED);
      expect(await pending).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports itself available only where the browser has geolocation', () => {
    expect(isGeolocationAvailable()).toBe(false);
    expect(renderHook(() => useGeolocationPostcode()).result.current.available).toBe(false);

    installGeolocation(fix(1, 2));

    expect(isGeolocationAvailable()).toBe(true);
    expect(renderHook(() => useGeolocationPostcode()).result.current.available).toBe(true);
  });
});
