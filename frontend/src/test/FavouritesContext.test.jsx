import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../api/favouritesApi.js', () => ({ fetchFavourites: vi.fn(), setFavourite: vi.fn() }));
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));

import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';
import { fetchAreaDirectory } from '../api/areasApi.js';
import {
  FavouritesProvider, MAX_LOAD_RETRIES, OFF_FAVOURITES, RETRY_MS, useFavourites,
} from '../context/FavouritesContext.jsx';
import FavouriteToggle, { useFavouriteUpsell } from '../components/shared/FavouriteToggle.jsx';

const BODY = { places: [{ locationId: 1, createdAt: 't' }], areas: [], locationIds: [1] };

function Probe() {
  const f = useFavourites();
  const upsell = useFavouriteUpsell();
  return (
    <div>
      <span data-testid="status">{f.status}</span>
      <span data-testid="ids">{[...f.locationIds].join(',')}</span>
      <span data-testid="areas">{f.starredAreas.map((a) => a.name).join(',')}</span>
      <FavouriteToggle kind="place" id={2} name="Steel Rigg" upsell={upsell} />
    </div>
  );
}

/** The latest context value a Spy saw, written in an effect so a test can call the actions directly. */
const spy = { current: null };
function Spy() {
  const f = useFavourites();
  useEffect(() => { spy.current = f; });
  return <span data-testid="spy-status">{f.status}</span>;
}

const mountProbe = (pro = true) => render(<FavouritesProvider pro={pro}><Probe /></FavouritesProvider>);

/** The provider: who fetches, the optimistic toggle with its revert, the cap message and the Undo. */
describe('FavouritesProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchFavourites.mockResolvedValue(BODY);
    fetchAreaDirectory.mockResolvedValue([]);
  });

  it('⚠️ a Lite reader never calls the endpoint and is locked', async () => {
    mountProbe(false);
    expect(screen.getByTestId('status')).toHaveTextContent('locked');
    await act(async () => { await Promise.resolve(); });
    expect(fetchFavourites).not.toHaveBeenCalled();
    expect(fetchAreaDirectory).not.toHaveBeenCalled();
    expect(screen.queryByTestId('undo-toast')).toBeNull();
    expect(screen.queryByTestId('undo-toast-idle')).toBeNull();
  });

  it('a Pro reader loads the served list: loading, then ready', async () => {
    mountProbe();
    expect(screen.getByTestId('status')).toHaveTextContent('loading');
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
    expect(screen.getByTestId('ids')).toHaveTextContent('1');
  });

  it('a failed load is an error state with no toggle (never a wrong aria-pressed)', async () => {
    fetchFavourites.mockRejectedValue(new Error('down'));
    mountProbe();
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error'));
    expect(screen.queryByTestId('favourite-toggle')).toBeNull();
  });

  it('stars optimistically, then takes the server list, and offers Undo', async () => {
    let resolve;
    setFavourite.mockReturnValue(new Promise((r) => { resolve = r; }));
    mountProbe();
    const toggle = await screen.findByTestId('favourite-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    // Before the server answers the button is already pressed.
    expect(screen.getByTestId('favourite-toggle')).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() => expect(setFavourite).toHaveBeenCalledWith('place', 2, true));
    await act(async () => {
      resolve({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] });
    });
    expect(screen.getByTestId('ids')).toHaveTextContent('1,2');
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('Added Steel Rigg to your places.');
  });

  it('Undo takes it back through the server and says Undone, with no second Undo', async () => {
    setFavourite.mockResolvedValueOnce({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] });
    setFavourite.mockResolvedValueOnce(BODY);
    mountProbe();
    fireEvent.click(await screen.findByTestId('favourite-toggle'));
    fireEvent.click(await screen.findByTestId('undo-toast-undo'));
    await waitFor(() => expect(setFavourite).toHaveBeenLastCalledWith('place', 2, false));
    await waitFor(() => expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('Undone.'));
    expect(screen.queryByTestId('undo-toast-undo')).toBeNull();
    expect(screen.getByTestId('favourite-toggle')).toHaveAttribute('aria-pressed', 'false');
  });

  it('⚠️ a refused star at the cap reverts and prints the server sentence', async () => {
    setFavourite.mockRejectedValue({ response: { status: 409, data: { error: 'You can keep up to 25 places.' } } });
    mountProbe();
    fireEvent.click(await screen.findByTestId('favourite-toggle'));
    await waitFor(() => expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('You can keep up to 25 places.'));
    expect(screen.getByTestId('favourite-toggle')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByTestId('undo-toast-undo')).toBeNull();
  });

  it('any other failure reverts and says so in words', async () => {
    setFavourite.mockRejectedValue(new Error('network'));
    mountProbe();
    fireEvent.click(await screen.findByTestId('favourite-toggle'));
    await waitFor(() => expect(screen.getByTestId('undo-toast-status')).toHaveTextContent("Couldn't update your places."));
    expect(screen.getByTestId('favourite-toggle')).toHaveAttribute('aria-pressed', 'false');
  });

  it('resolves starred areas by name once the directory lands, and only asks when an area is starred', async () => {
    fetchFavourites.mockResolvedValue({
      places: [], areas: [{ areaId: 5 }], locationIds: [7, 8],
    });
    fetchAreaDirectory.mockResolvedValue([
      { id: 5, name: "Hadrian's Wall", locationIds: [7, 8] }, { id: 6, name: 'Other', locationIds: [] },
    ]);
    mountProbe();
    await waitFor(() => expect(screen.getByTestId('areas')).toHaveTextContent("Hadrian's Wall"));
    expect(screen.getByTestId('areas')).not.toHaveTextContent('Other');
  });

  it('the off value answers every question with no, and its actions do nothing', async () => {
    expect(OFF_FAVOURITES.isPlaceStarred(1)).toBe(false);
    expect(OFF_FAVOURITES.isAreaStarred(1)).toBe(false);
    await expect(OFF_FAVOURITES.setStar()).resolves.toBe(false);
    await expect(OFF_FAVOURITES.toggle()).resolves.toBe(false);
  });

  it('stars and un-stars an AREA through the same path, and ignores a press before the list has landed', async () => {
    fetchFavourites.mockImplementation(() => new Promise(() => {}));
    const first = render(<FavouritesProvider pro><Spy /></FavouritesProvider>);
    await expect(spy.current.toggle('place', 2, 'Steel Rigg')).resolves.toBe(false);
    expect(setFavourite).not.toHaveBeenCalled();
    first.unmount();

    fetchFavourites.mockResolvedValue({ places: [], areas: [{ areaId: 4 }], locationIds: [] });
    fetchAreaDirectory.mockResolvedValue([]);
    setFavourite.mockResolvedValue({ places: [], areas: [], locationIds: [] });
    render(<FavouritesProvider pro><Spy /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    await act(async () => { await spy.current.toggle('area', 4, "Hadrian's Wall"); });
    expect(setFavourite).toHaveBeenCalledWith('area', 4, false);
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent("Removed Hadrian's Wall (area) from your places.");
  });

  it('⚠️ serialises changes: the second request waits for the first, and only the LAST answer is applied and announced', async () => {
    let first;
    setFavourite
      .mockReturnValueOnce(new Promise((r) => { first = r; }))
      .mockResolvedValueOnce({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] });
    render(<FavouritesProvider pro><Spy /><Probe /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    let both;
    await act(async () => {
      both = Promise.all([
        spy.current.setStar('place', 2, 'Steel Rigg', true),
        spy.current.setStar('place', 3, 'Craster', true),
      ]);
      await Promise.resolve();
    });
    // The second request has NOT gone out while the first is in flight.
    expect(setFavourite).toHaveBeenCalledTimes(1);
    await act(async () => { first({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] }); await both; });
    expect(setFavourite).toHaveBeenCalledTimes(2);
    expect(setFavourite).toHaveBeenLastCalledWith('place', 3, true);
    // One announcement, for the answer that is applied, and the served list is what state holds.
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('Added Craster to your places.');
    expect(screen.getByTestId('ids')).toHaveTextContent('1,2');
  });

  it('⚠️ converges on the server after a refused change: a starred area\'s members are never left out of locationIds', async () => {
    const SERVED = { places: [], areas: [{ areaId: 9 }], locationIds: [20, 21] };
    fetchFavourites.mockResolvedValueOnce(BODY).mockResolvedValue(SERVED);
    // Star an area (the server expands its members), then a place that is refused.
    setFavourite
      .mockResolvedValueOnce({ places: [{ locationId: 1 }], areas: [{ areaId: 9 }], locationIds: [1, 20, 21] })
      .mockRejectedValueOnce({ response: { status: 500 } });
    render(<FavouritesProvider pro><Spy /><Probe /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    await act(async () => {
      await Promise.all([
        spy.current.setStar('area', 9, "Hadrian's Wall", true),
        spy.current.setStar('place', 2, 'Steel Rigg', true),
      ]);
    });
    await waitFor(() => expect(fetchFavourites).toHaveBeenCalledTimes(2));
    // The re-read replaced the optimistic state: the area's members are present, the refused place is not.
    await waitFor(() => expect(screen.getByTestId('ids')).toHaveTextContent('20,21'));
    expect(spy.current.isAreaStarred(9)).toBe(true);
    expect(spy.current.isPlaceStarred(2)).toBe(false);
  });

  it('retries a failed first load quietly, bounded, instead of switching favourites off for the session', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      fetchFavourites.mockRejectedValueOnce(new Error('down')).mockResolvedValue(BODY);
      mountProbe();
      await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error'));
      await act(async () => { await vi.advanceTimersByTimeAsync(RETRY_MS + 10); });
      await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
      expect(fetchFavourites).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops retrying after the bound', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      fetchFavourites.mockRejectedValue(new Error('down'));
      mountProbe();
      for (let i = 0; i <= MAX_LOAD_RETRIES + 1; i += 1) {
        await act(async () => { await vi.advanceTimersByTimeAsync(RETRY_MS * (MAX_LOAD_RETRIES + 1) + 10); });
      }
      expect(fetchFavourites).toHaveBeenCalledTimes(MAX_LOAD_RETRIES + 1);
      expect(screen.getByTestId('status')).toHaveTextContent('error');
    } finally {
      vi.useRealTimers();
    }
  });

  it('retries a failed area directory later, and says how many starred areas are unnamed meanwhile', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      fetchFavourites.mockResolvedValue({ places: [], areas: [{ areaId: 5 }], locationIds: [7] });
      fetchAreaDirectory.mockRejectedValueOnce(new Error('down')).mockResolvedValue([{ id: 5, name: "Hadrian's Wall", locationIds: [7] }]);
      mountProbe();
      await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
      await waitFor(() => expect(fetchAreaDirectory).toHaveBeenCalledTimes(1));
      expect(screen.getByTestId('areas')).toHaveTextContent('');
      await act(async () => { await vi.advanceTimersByTimeAsync(RETRY_MS + 10); });
      await waitFor(() => expect(screen.getByTestId('areas')).toHaveTextContent("Hadrian's Wall"));
    } finally {
      vi.useRealTimers();
    }
  });

  it('hands focus back to the control that was pressed, even though the answer lands after focus moved', async () => {
    let resolve;
    setFavourite.mockReturnValue(new Promise((r) => { resolve = r; }));
    mountProbe();
    const toggle = await screen.findByTestId('favourite-toggle');
    toggle.focus();
    fireEvent.click(toggle);
    document.body.focus();
    const other = document.createElement('button');
    document.body.appendChild(other);
    other.focus();
    await act(async () => { resolve({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] }); });
    fireEvent.click(await screen.findByTestId('undo-toast-undo'));
    expect(toggle).toHaveFocus();
    other.remove();
  });

  it('a reply that lands after the provider has gone, or a failed directory, changes nothing', async () => {
    let late;
    fetchFavourites.mockReturnValue(new Promise((r) => { late = r; }));
    const { unmount } = render(<FavouritesProvider pro><Probe /></FavouritesProvider>);
    unmount();
    await act(async () => { late(BODY); });

    fetchFavourites.mockResolvedValue({ places: [], areas: [{ areaId: 5 }], locationIds: [] });
    fetchAreaDirectory.mockRejectedValue(new Error('down'));
    render(<FavouritesProvider pro><Probe /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
    expect(screen.getByTestId('areas')).toHaveTextContent('');
  });

  it('outside a provider every consumer reads the off value', () => {
    const Off = () => <span data-testid="off">{useFavourites().status}</span>;
    render(<Off />);
    expect(screen.getByTestId('off')).toHaveTextContent('off');
  });
});
