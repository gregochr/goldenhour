import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../api/favouritesApi.js', () => ({ fetchFavourites: vi.fn(), setFavourite: vi.fn() }));
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));

import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';
import { fetchAreaDirectory } from '../api/areasApi.js';
import { FavouritesProvider, OFF_FAVOURITES, useFavourites } from '../context/FavouritesContext.jsx';
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
    expect(setFavourite).toHaveBeenCalledWith('place', 2, true);
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

  it('applies only the newest answer: a slow older reply cannot put back what a newer one changed', async () => {
    let slow;
    setFavourite
      .mockReturnValueOnce(new Promise((r) => { slow = r; }))
      .mockResolvedValueOnce({ places: [], areas: [], locationIds: [] });
    render(<FavouritesProvider pro><Spy /><Probe /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    await act(async () => {
      void spy.current.setStar('place', 2, 'Steel Rigg', true);
      await spy.current.setStar('place', 1, 'Bamburgh', false);
    });
    await act(async () => { slow({ places: [{ locationId: 1 }, { locationId: 2 }], areas: [], locationIds: [1, 2] }); });
    expect(screen.getByTestId('ids')).toHaveTextContent('');
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
